#!/usr/bin/env python3
"""V3.1-I · P1-B1 数据留存清理 E2E（自起自停 fat jar，只 kill 本轮 PID）。

五个场景，逐步收口：
  ┃ 场景 1（dev·dry-run）  ：只统计不删除 —— 「这一刀下去会删多少」必须能先看见
  ┃ 场景 2（dev·默认）    ：真实清理，删旧留新；顺带验证租户表在零租户下不误删
  ┃ 场景 3（dev·硬下限）  ：保留天数配 0 → 拒绝执行并抛异常，数据一行不动
  ┃ 场景 4（dev·单次上限）：max-delete-per-run=2 → 只删 2 条并标记 truncated
  ┃ 场景 5（dev·总开关）  ：enabled=false → 整轮 SKIPPED，一次都不查库

为什么必须真起进程：
  ① 「物理删除」和「@TableLogic 软删」在单测里看不出区别（MockMapper 都返回行数），
     只有对着真库 count 才能证明空间确实被回收；
  ② 留存清理依赖 MP 分页插件、租户行级过滤、JobExecutionHelper 落 sys_job_log
     这一整条装配链，任何一环缺失都不会报错、只会静默不清理；
  ③ docsite 给的运维覆盖方式是 PIVOTOS_RETENTION_* 环境变量，
     「写了不生效」这类文档反向杀伤只能靠真起服验证。

用法：
    python3 scripts/e2e/retention_cleanup_test.py                 # 全跑
    python3 scripts/e2e/retention_cleanup_test.py --only=dryrun   # 只跑场景 1
    python3 scripts/e2e/retention_cleanup_test.py --only=default  # 只跑场景 2
    python3 scripts/e2e/retention_cleanup_test.py --only=floor    # 只跑场景 3
    python3 scripts/e2e/retention_cleanup_test.py --only=limit    # 只跑场景 4
    python3 scripts/e2e/retention_cleanup_test.py --only=disabled # 只跑场景 5
"""
import argparse
import json
import os
import random
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAR = os.path.join(ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
WORK_ROOT = "/tmp/pivotos-v31i-retention-e2e"

TABLE = "sys_login_log"
MARK = "__retention_e2e__"
OLD_COUNT = 5     # 400 天前，应被清理
NEW_COUNT = 3     # 今天，应保留

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


# ────────────────────────── 数据库 fixture ──────────────────────────

def connect_db():
    import pymysql
    return pymysql.connect(host=os.environ.get("PIVOTOS_DEV_MYSQL_HOST", "175.24.176.176"),
                           port=int(os.environ.get("PIVOTOS_DEV_MYSQL_PORT", "3306")),
                           user=os.environ.get("PIVOTOS_DEV_MYSQL_USER", "root"),
                           password=os.environ.get("PIVOTOS_DEV_MYSQL_PASSWORD", "mysql_DNCi3f"),
                           database=os.environ.get("PIVOTOS_DEV_MYSQL_DATABASE", "pivotos_dev"),
                           charset="utf8mb4")


def purge_fixture():
    """只删本脚本自己造的行（按 username 标记），不动任何存量数据。"""
    conn = connect_db()
    try:
        with conn.cursor() as cur:
            cur.execute(f"DELETE FROM {TABLE} WHERE username = %s", (MARK,))
            n = cur.rowcount
        conn.commit()
        return n
    finally:
        conn.close()


def seed_fixture():
    """造 5 条过期 + 3 条新鲜，返回造完后的总行数。"""
    conn = connect_db()
    try:
        with conn.cursor() as cur:
            for i in range(OLD_COUNT):
                cur.execute(
                    "INSERT INTO sys_login_log (id, username, ip, user_agent, status, msg, "
                    "login_time, create_by, create_time) VALUES (%s, %s, '127.0.0.1', 'e2e', 0, "
                    "'e2e-old', DATE_SUB(NOW(), INTERVAL 400 DAY), 1, NOW())",
                    (random.randint(10 ** 17, 10 ** 18 - 1), MARK))
            for i in range(NEW_COUNT):
                cur.execute(
                    "INSERT INTO sys_login_log (id, username, ip, user_agent, status, msg, "
                    "login_time, create_by, create_time) VALUES (%s, %s, '127.0.0.1', 'e2e', 0, "
                    "'e2e-new', NOW(), 1, NOW())",
                    (random.randint(10 ** 17, 10 ** 18 - 1), MARK))
        conn.commit()
        return count_marked()
    finally:
        conn.close()


def count_marked():
    conn = connect_db()
    try:
        with conn.cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {TABLE} WHERE username = %s", (MARK,))
            return cur.fetchone()[0]
    finally:
        conn.close()


def count_old():
    """标记行里 login_time 早于 30 天前的条数（即「应被默认窗口清理」的量）"""
    conn = connect_db()
    try:
        with conn.cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {TABLE} WHERE username = %s "
                        f"AND login_time < DATE_SUB(NOW(), INTERVAL 30 DAY)", (MARK,))
            return cur.fetchone()[0]
    finally:
        conn.close()


# ────────────────────────── 起停 ──────────────────────────

def port_busy(port=8080) -> bool:
    # 注意：不能用 bind 探测（BSD/macOS 下占用方带 SO_REUSEADDR 时 bind 会假绿）
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def clean_env(extra=None):
    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    if extra:
        env.update(extra)
    return env


def start_server(env_extra=None, timeout=240):
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。"
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    tag = (env_extra or {}).get("E2E_TAG", "default")
    log_path = os.path.join(WORK_ROOT, f"{tag}.log")
    cwd = os.path.join(WORK_ROOT, f"cwd-{tag}")
    os.makedirs(cwd, exist_ok=True)
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev"]
    log("START", f"起服（{tag}）日志={log_path}")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT,
                            env=clean_env(env_extra), cwd=cwd)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端提前退出（exit={proc.returncode}），见 {log_path}")
        try:
            if http_get("/health/live")[0] == 200:
                log("START", f"后端就绪 PID={proc.pid}")
                return proc, fh, log_path
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 {timeout}s 未就绪，见 {log_path}")


def stop_server(proc, fh):
    try:
        if proc and proc.poll() is None:
            log("STOP", f"kill 本轮 PID {proc.pid}")
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.send_signal(signal.SIGKILL)
                proc.wait(timeout=10)
    finally:
        if fh:
            fh.close()
    for _ in range(30):
        if not port_busy():
            return
        time.sleep(1)


def read_log(log_path):
    with open(log_path, "rb") as f:
        return f.read().decode("utf-8", "replace")


# ────────────────────────── HTTP 辅助 ──────────────────────────

def http_get(path, headers=None, timeout=15):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def post_json(path, payload=None, headers=None):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload or {}).encode(),
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def auth_headers():
    status, body = post_json("/system/auth/login", {"username": "admin", "password": "admin123"})
    assert status == 200 and body.get("code") == 0, f"登录失败：{status} {body}"
    # 本项目是裸 token，不带 "Bearer " 前缀——加了前缀会被判 1002 未登录
    return {"Authorization": body["data"]["token"]}


def trigger(handler, headers):
    # HTTP 状态码恒 200，成败必须读 body 的 code：0=成功、1002=未登录、1004=端点不存在
    status, body = post_json(f"/system/joblog/trigger/{handler}", {}, headers)
    return status, body


# ────────────────────────── 场景 ──────────────────────────

def scenario(tag, env_extra, assertions):
    """起服 → 断言 → 停服。停服放在 finally，且内部再嵌一层保证 purge 抛错也不跳过停服。"""
    proc = fh = None
    try:
        purge_fixture()
        total = seed_fixture()
        check(f"{tag}-S0", total == OLD_COUNT + NEW_COUNT,
              f"fixture 就位：{total} 行（{OLD_COUNT} 旧 + {NEW_COUNT} 新）")
        proc, fh, log_path = start_server({**env_extra, "E2E_TAG": tag})
        headers = auth_headers()
        assertions(tag, headers, log_path)
    finally:
        try:
            stop_server(proc, fh)
        finally:
            try:
                left = purge_fixture()
                log("CLEAN", f"fixture 自清 {left} 行（应为 0）")
            except Exception as e:  # 清场失败不能吞掉停服，但要留下证据
                log("CLEAN", f"fixture 自清失败（需人工确认）：{e}")


def sc_dryrun(tag, headers, log_path):
    status, body = trigger("loginLogCleanup", headers)
    check(f"{tag}-T1", status == 200 and body.get("code") == 0,
          f"手动触发成功（HTTP {status}, code={body.get('code')}）")
    time.sleep(2)  # 执行是同步的，但落日志有缓冲
    text = read_log(log_path)
    line = [l for l in text.splitlines() if "retention[sys_login_log]" in l]
    check(f"{tag}-T2", bool(line), f"日志出现留存报告：{line[-1] if line else '（无）'}")
    last = line[-1] if line else ""
    check(f"{tag}-T3", "dryRun=true" in last, "报告标记 dryRun=true")
    check(f"{tag}-T4", f"matched={OLD_COUNT}" in last, f"演练仍能算出待删 {OLD_COUNT} 条")
    check(f"{tag}-T5", "deleted=0" in last, "演练一条都没删")
    check(f"{tag}-T6", count_marked() == OLD_COUNT + NEW_COUNT, "库内行数完全不变")


def sc_default(tag, headers, log_path):
    status, body = trigger("loginLogCleanup", headers)
    check(f"{tag}-T1", status == 200 and body.get("code") == 0,
          f"手动触发成功（HTTP {status}, code={body.get('code')}）")
    time.sleep(2)
    check(f"{tag}-T2", count_old() == 0, "过期的 5 条已被物理删除（不是置 deleted=1）")
    check(f"{tag}-T3", count_marked() == NEW_COUNT, f"新鲜的 {NEW_COUNT} 条完整保留")
    text = read_log(log_path)
    line = [l for l in text.splitlines() if "retention[sys_login_log]" in l]
    last = line[-1] if line else ""
    check(f"{tag}-T4", f"deleted={OLD_COUNT}" in last, f"报告记录删除 {OLD_COUNT} 条")

    # 租户表路径：dev 库当前 0 租户，逐租户清理应退化为 0 租户且不误删
    status2, body2 = trigger("messageRetentionCleanup", headers)
    check(f"{tag}-T5", status2 == 200 and body2.get("code") == 0,
          f"消息留存任务可触发（HTTP {status2}, code={body2.get('code')}）")
    time.sleep(2)
    text2 = read_log(log_path)
    msg_line = [l for l in text2.splitlines() if "retention[msg_message]" in l]
    check(f"{tag}-T6", bool(msg_line), "租户表清理有执行报告")
    if msg_line:
        check(f"{tag}-T7", "tenants=0" in msg_line[-1],
              "零租户时退化为 tenants=0，不产生任何跨租户删除")


def sc_floor(tag, headers, log_path):
    status, body = trigger("loginLogCleanup", headers)
    check(f"{tag}-T1", status == 200, f"触发请求可达（HTTP {status}）")
    time.sleep(2)
    text = read_log(log_path)
    check(f"{tag}-T2", "留存策略拒绝执行" in text, "保留天数 0 天被硬下限拦下并抛异常")
    check(f"{tag}-T3", "全表清空" in text, "拒绝原因明确写出「配成 0 等于全表清空」")
    check(f"{tag}-T4", count_marked() == OLD_COUNT + NEW_COUNT,
          f"拒绝执行后数据一行未动（仍 {OLD_COUNT + NEW_COUNT} 行）")


def sc_limit(tag, headers, log_path):
    status, body = trigger("loginLogCleanup", headers)
    check(f"{tag}-T1", status == 200 and body.get("code") == 0, "触发成功")
    time.sleep(2)
    check(f"{tag}-T2", count_marked() == OLD_COUNT - 2 + NEW_COUNT,
          f"单次上限 2 → 只删 2 条，剩 {OLD_COUNT - 2 + NEW_COUNT} 行")
    text = read_log(log_path)
    line = [l for l in text.splitlines() if "retention[sys_login_log]" in l]
    last = line[-1] if line else ""
    check(f"{tag}-T3", f"matched={OLD_COUNT}" in last, "待删总数仍报 5（上限只影响本批）")
    check(f"{tag}-T4", "truncated=true" in last, "标记 truncated=true（还有下一轮）")


def sc_disabled(tag, headers, log_path):
    status, body = trigger("loginLogCleanup", headers)
    check(f"{tag}-T1", status == 200 and body.get("code") == 0, "触发成功（执行体本身不报错）")
    time.sleep(2)
    text = read_log(log_path)
    line = [l for l in text.splitlines() if "retention[sys_login_log]" in l]
    check(f"{tag}-T2", bool(line) and "SKIPPED" in line[-1], "报告明确 SKIPPED")
    check(f"{tag}-T3", count_marked() == OLD_COUNT + NEW_COUNT, "总开关关闭后一行不删")
    # 与「删了 0 条」区分开：SKIPPED 摘要不会带 deleted=0 这种易混淆表述
    check(f"{tag}-T4", "总开关已关闭" in (line[-1] if line else ""), "摘要给出跳过原因")


SCENARIOS = {
    "dryrun": (sc_dryrun, {"PIVOTOS_RETENTION_DRY_RUN": "true"}),
    "default": (sc_default, {}),
    "floor": (sc_floor, {"PIVOTOS_RETENTION_LOGIN_LOG_DAYS": "0"}),
    "limit": (sc_limit, {"PIVOTOS_RETENTION_MAX_DELETE_PER_RUN": "2"}),
    "disabled": (sc_disabled, {"PIVOTOS_RETENTION_ENABLED": "false"}),
}


def main():
    parser = argparse.ArgumentParser(description="V3.1-I 数据留存清理 E2E")
    parser.add_argument("--only", type=str, default=None,
                        help="只跑指定场景：dryrun|default|floor|limit|disabled")
    args = parser.parse_args()

    os.makedirs(WORK_ROOT, exist_ok=True)
    if not os.path.exists(JAR):
        log("FAIL", f"找不到 fat jar：{JAR}（先 mvn clean package）")
        return 1

    todo = [args.only] if args.only else list(SCENARIOS)
    failed = []
    for name in todo:
        if name not in SCENARIOS:
            log("FAIL", f"未知场景 {name}")
            return 1
        fn, env_extra = SCENARIOS[name]
        log("=" * 70, f"场景 {name}　env={env_extra}")
        try:
            scenario(name, env_extra, fn)
        except Exception as e:
            log("FAIL", f"场景 {name} 异常：{e}")
            failed.append(name)

    log("=" * 70, f"总计 {len(results)} 断言，失败场景 {len(failed)}")
    for tag, ok in results:
        if not ok:
            log("FAILED", tag)
    if failed:
        log("RESULT", f"FAIL 场景：{failed}")
        return 1
    log("RESULT", "ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
