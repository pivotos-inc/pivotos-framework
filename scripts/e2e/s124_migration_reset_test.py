#!/usr/bin/env python3
"""S124 · L10 清偿 E2E：迁移任务 plan 复位端点 POST /migration/task/reset + L9 数据源溯源日志。

覆盖（单场景，脚本自起自停一套 dev 后端，8080）：
  ① 起服冒烟：Flyway `Successfully validated` + 登录 code=0 + 匿名 /sse 401
  ② L9 守卫：启动日志出现「[DataSource] 生效数据源溯源：database=pivotos_dev」
  ③ reset 鉴权：匿名调用被拦
  ④ reset 负例：任务不存在 → 8000；任务处于 CREATED → 8001（防抹掉真实产物）
  ⑤ reset 正例：任务置为 ANALYZED 后复位 → 步骤清零、未落盘产物清零、
     **applied=true 的产物行必须留下**（否则 rollback 找不到磁盘文件）
  ⑥ 自清：复位后删除本轮自建的任务/步骤/产物行，并复核归零

用法：
    python3 scripts/e2e/s124_migration_reset_test.py
"""
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

import pymysql
import requests

BASE = "http://localhost:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
JAR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                   "pivotos-admin-server", "target", "pivotos-admin-server.jar"))
LOG_DIR = "/tmp/pivotos-s124-reset-e2e"
LOG_PATH = os.path.join(LOG_DIR, "server.log")

DB = dict(host="175.24.176.176", port=3306, user="root", password="mysql_DNCi3f",
          database="pivotos_dev", charset="utf8mb4")

results = []


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(tag, cond, msg):
    results.append((tag, bool(cond)))
    if cond:
        log("PASS", f"{tag} {msg}")
    else:
        log("FAIL", f"{tag} {msg}")
        raise AssertionError(f"{tag} 断言失败：{msg}")


# ────────────────────────── 后端生命周期 ──────────────────────────

def port_busy(port=8080) -> bool:
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def start_server():
    if port_busy():
        raise RuntimeError(
            "8080 已被占用（可能是用户自有进程或上一轮遗留）。"
            "先确认归属：lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    os.makedirs(LOG_DIR, exist_ok=True)
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev"]
    env = dict(os.environ)
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    log("START", f"起服 PID 待定，日志={LOG_PATH}")
    fh = open(LOG_PATH, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=env)
    log("START", f"PID={proc.pid}")

    deadline = time.time() + 240
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端提前退出（exit={proc.returncode}），见 {LOG_PATH}")
        try:
            req = urllib.request.Request(
                f"{BASE}/system/auth/login",
                data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200 and json.loads(resp.read()).get("code") == 0:
                    log("START", "后端就绪")
                    return proc, fh
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 240s 未就绪，见 {LOG_PATH}")


def stop_server(proc, fh):
    if proc and proc.poll() is None:
        log("STOP", f"kill 本轮 PID {proc.pid}")
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.send_signal(signal.SIGKILL)
            proc.wait(timeout=10)
    if fh:
        fh.close()
    for _ in range(30):
        try:
            urllib.request.urlopen(f"{BASE}/system/auth/login", timeout=2)
        except Exception:
            return
        time.sleep(1)


# ────────────────────────── DB 辅助 ──────────────────────────

def db():
    return pymysql.connect(**DB)


def seed(task_id, name):
    """自建任务 + 2 条步骤 + 2 条产物（一条已落盘、一条未落盘）"""
    conn = db()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO migration_task (id, name, description, status, total_steps, deleted) "
                "VALUES (%s,%s,%s,0,0,0)",
                (task_id, name, "S124 E2E 自建任务，跑完自清"))
            for step_no in (1, 2):
                cur.execute(
                    "INSERT INTO migration_step (id, task_id, step_no, name, step_type, "
                    "module_id, module_name, status, deleted) "
                    "VALUES (%s,%s,%s,%s,'BACKEND','m','模块',0,0)",
                    (task_id + step_no, task_id, step_no, f"步骤{step_no}"))
            # 一条已落盘（applied=1）+ 一条未落盘（applied=0）
            cur.execute(
                "INSERT INTO migration_artifact (id, task_id, step_id, artifact_type, "
                "relative_path, content_hash, applied, deleted) "
                "VALUES (%s,%s,%s,'JAVA','a/b.java','h1',1,0)",
                (task_id + 11, task_id, task_id + 1))
            cur.execute(
                "INSERT INTO migration_artifact (id, task_id, step_id, artifact_type, "
                "relative_path, content_hash, applied, deleted) "
                "VALUES (%s,%s,%s,'JAVA','a/c.java','h2',0,0)",
                (task_id + 12, task_id, task_id + 2))
        conn.commit()
    finally:
        conn.close()


def set_status(task_id, status):
    conn = db()
    try:
        with conn.cursor() as cur:
            cur.execute("UPDATE migration_task SET status=%s WHERE id=%s", (status, task_id))
        conn.commit()
    finally:
        conn.close()


def counts(task_id):
    conn = db()
    try:
        with conn.cursor() as cur:
            # 必须带 deleted=0：migration_* 全部继承 BaseDO，走 MP 逻辑删除，
            # delete(wrapper) 实际是 UPDATE ... SET deleted=1（物理行仍在）
            cur.execute("SELECT COUNT(*) FROM migration_step WHERE task_id=%s AND deleted=0",
                        (task_id,))
            steps = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM migration_artifact "
                        "WHERE task_id=%s AND applied=0 AND deleted=0", (task_id,))
            pending = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM migration_artifact "
                        "WHERE task_id=%s AND applied=1 AND deleted=0", (task_id,))
            applied = cur.fetchone()[0]
            cur.execute("SELECT status FROM migration_task WHERE id=%s AND deleted=0", (task_id,))
            row = cur.fetchone()
            status = row[0] if row else None
        return steps, pending, applied, status
    finally:
        conn.close()


def purge(task_id):
    conn = db()
    try:
        with conn.cursor() as cur:
            for table in ("migration_artifact", "migration_step"):
                cur.execute(f"DELETE FROM {table} WHERE task_id=%s", (task_id,))
            cur.execute("DELETE FROM migration_task WHERE id=%s", (task_id,))
        conn.commit()
    finally:
        conn.close()


# ────────────────────────── 断言 ──────────────────────────

def login():
    r = requests.post(f"{BASE}/system/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=60)
    body = r.json()
    assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
    return {"Authorization": body["data"]["token"]}


def reset(hdr, task_id):
    return requests.post(f"{BASE}/migration/task/reset",
                         params={"taskId": task_id}, headers=hdr, timeout=60)


def main():
    proc, fh = start_server()
    task_id = 990000000000000001  # 哨兵主键，远小于 BIGINT 上限
    try:
        hdr = login()

        # ① 起服冒烟：匿名 /sse 必须 401
        anon = requests.get(f"{BASE}/sse", timeout=30)
        check("①冒烟", anon.status_code == 401, f"匿名 /sse 401（实际 {anon.status_code}）")

        # ② L9 守卫：dev 库必须在启动日志里被溯源出来
        with open(LOG_PATH, "rb") as f:
            text = f.read().decode("utf-8", errors="ignore")
        check("②L9", "[DataSource] 生效数据源溯源" in text, "启动日志含数据源溯源行")
        check("②L9", "database=pivotos_dev" in text, "溯源结果 database=pivotos_dev")
        check("②Flyway", "Successfully validated" in text, "Flyway 校验通过（未用 validate-on-migrate=false）")

        # ④ 负例：任务不存在 → 8000
        r = reset(hdr, 990000000000000999)
        check("④负例", r.json().get("code") == 8000, f"不存在的任务返回 8000（实际 {r.json().get('code')}）")

        # ④ 负例：CREATED 状态不允许复位 → 8001
        seed(task_id, "s124-reset-e2e")
        r = reset(hdr, task_id)
        check("④负例", r.json().get("code") == 8001,
              f"CREATED 状态复位被拒 8001（实际 {r.json().get('code')}）")

        # ③ 鉴权：匿名调用不得删成（本端点带 @SaCheckLogin）
        # 注意断言方式：不能只看 code != 0——任务不存在时匿名也会拿到 8000，
        # 那是「没找到」而不是「被拦」。必须把任务置为可复位状态再验证「数据没被删」。
        set_status(task_id, 4)
        anon_reset = requests.post(f"{BASE}/migration/task/reset",
                                   params={"taskId": task_id}, timeout=30)
        body = anon_reset.json() if anon_reset.headers.get("content-type", "").startswith(
            "application/json") else {}
        s0, p0, a0, _ = counts(task_id)
        check("③鉴权", body.get("code") != 0,
              f"匿名 reset 被拦（HTTP {anon_reset.status_code}, code={body.get('code')}）")
        check("③鉴权", s0 == 2 and p0 == 1,
              f"匿名 reset 未删掉任何数据（steps={s0}, 未落盘产物={p0}）")

        # ⑤ 正例：ANALYZED(4) 状态下复位
        r = reset(hdr, task_id)
        code = r.json().get("code")
        steps, pending, applied, status = counts(task_id)
        check("⑤正例", code == 0, f"复位成功 code=0（实际 {code}）")
        check("⑤正例", steps == 0, f"步骤清零（实际 {steps}）")
        check("⑤正例", pending == 0, f"未落盘产物清零（实际 {pending}）")
        check("⑤正例", applied == 1, f"已落盘产物行必须保留（实际 {applied}）")
        check("⑤正例", status == 4, f"任务回到 ANALYZED(4)（实际 {status}）")
    finally:
        # 自清失败也必须停服：本轮踩过一次——purge 抛错导致 stop_server 被跳过，
        # 遗留进程占着 8080，下一轮脚本会「登录成功但连的是上一轮的旧 jar」，
        # 断言绿红全乱且极难排查（端口占用检测用 lsof -Pan -i:8080 -sTCP:LISTEN）。
        try:
            purge(task_id)
            s, p, a, st = counts(task_id)
            log("CLEAN", f"自清复核：steps={s} artifacts={p + a} task={st}")
        finally:
            stop_server(proc, fh)

    total = len(results)
    failed = [t for t, ok in results if not ok]
    print(f"\n合计：{total - len(failed)}/{total} PASS")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
