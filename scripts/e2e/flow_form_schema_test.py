#!/usr/bin/env python3
"""V3.1-D · flow_form 漂移修复的实证断言（自起自停 fat jar，只 kill 本轮 PID）。

背景：warm-flow 的 `warm-flow-plugin-ui-sb-web` 自带表单端点，`GET /warm-flow/published-form`
→ `FlowEngine.formService().list()` → `flow_form` 表；而 V1.2.21 只建了 8 张引擎表，缺表单表，
于是**登录后恒 500**（`Table 'pivotos_dev.flow_form' doesn't exist`）。V3.1-D 拍板走 A 路：
补 Flyway V1.2.53 建表，而不是屏蔽端点。

断言（逐条，全部实打）：
  F-1 匿名 GET /warm-flow/published-form 仍被闸门拦（code=1002）——证明不是靠放开端点换来的
  F-2 登录 GET /warm-flow/published-form 不再 500/1500（表存在，查询能走完）
  F-3 flow_form 表存在且列与 FlowForm 实体（1.8.7 javap 实测 15 列）逐一对齐
  F-4 V1.2.53 迁移已被应用且 success=1
  F-5 相邻链路未被误伤：登录 GET /warm-flow/query-def 仍可用

用法：
    python3 scripts/e2e/flow_form_schema_test.py
前置：8080 空闲（脚本自起自停）；库为 dev 实例，凭据从 application-dev.yml 运行时现读。
"""
import glob
import json
import os
import re
import shutil
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
DEV_YML = os.path.join(ROOT, "pivotos-admin-server", "src", "main", "resources", "application-dev.yml")
WORK_ROOT = "/tmp/pivotos-v31d-flowform"

# FlowForm 实体（warm-flow-mybatis-plus-core 1.8.7，javap 实测）的全部字段 → 列名
EXPECTED_COLUMNS = [
    "id", "create_time", "update_time", "create_by", "update_by", "del_flag",
    "form_code", "form_name", "version", "is_publish", "form_type", "form_path",
    "form_content", "ext", "tenant_id",
]

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


def port_busy(port=8080) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def start_server(timeout=300):
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。先确认归属："
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    cwd = os.path.join(WORK_ROOT, "dev-cwd")
    os.makedirs(cwd, exist_ok=True)
    log_path = os.path.join(WORK_ROOT, "dev-console.log")
    # 不带任何 PIVOTOS_* 覆盖：dev yml 自带的明文兜底就是 dev 形态的设计意图
    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev"]
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=env, cwd=cwd)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端提前退出（exit={proc.returncode}），见 {log_path}")
        try:
            if http_get("/health/live")[0] == 200:
                log("START", f"后端就绪 PID={proc.pid}")
                return proc, fh, cwd
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 {timeout}s 未就绪，见 {log_path}")


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
        if not port_busy():
            return
        time.sleep(1)


def http_get(path, headers=None, timeout=10):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def get_json(path, headers=None):
    status, raw = http_get(path, headers)
    try:
        return status, json.loads(raw)
    except Exception:
        return status, None


def login_token():
    req = urllib.request.Request(
        BASE + "/system/auth/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        body = json.loads(resp.read())
    assert body.get("code") == 0, f"登录失败：{body}"
    return body["data"]["token"]


def dev_db_conn_args():
    """从 application-dev.yml 现读凭据（绝不落脚本，也不落命令历史）"""
    text = open(DEV_YML, encoding="utf-8").read()

    def pick(pattern):
        m = re.search(pattern, text)
        return m.group(1).strip('"') if m else ""

    url = pick(r"url:\s*\$\{PIVOTOS_MYSQL_URL:([^}]+)\}")
    username = pick(r"username:\s*\$\{PIVOTOS_MYSQL_USERNAME:([^}]+)\}")
    password = pick(r"password:\s*\$\{PIVOTOS_MYSQL_PASSWORD:([^}]+)\}")
    m = re.search(r"jdbc:mysql://([^:/]+):(\d+)/([^?]+)", url)
    if not m:
        raise RuntimeError(f"未能从 application-dev.yml 解析出 MySQL 连接信息：{url}")
    return {"host": m.group(1), "port": int(m.group(2)), "database": m.group(3),
            "user": username, "password": password}


def query_flow_form():
    import pymysql
    args = dev_db_conn_args()
    conn = pymysql.connect(host=args["host"], port=args["port"], user=args["user"],
                           password=args["password"], database=args["database"],
                           charset="utf8mb4", connect_timeout=10)
    try:
        with conn.cursor() as cur:
            cur.execute("""SELECT COLUMN_NAME FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA=%s AND TABLE_NAME='flow_form'
                           ORDER BY ORDINAL_POSITION""", (args["database"],))
            columns = [r[0] for r in cur.fetchall()]
            cur.execute("""SELECT COUNT(*) FROM flyway_schema_history
                           WHERE version='1.2.53' AND success=1""")
            applied = cur.fetchone()[0]
        return columns, applied
    finally:
        conn.close()


def main():
    if os.path.exists(WORK_ROOT):
        shutil.rmtree(WORK_ROOT)
    os.makedirs(WORK_ROOT, exist_ok=True)

    proc, fh, cwd = start_server()
    try:
        # F-1 匿名仍被闸门拦（V3.1-C 的收口不能被这次改动放掉）
        status, body = get_json("/warm-flow/published-form")
        check("F-1", status == 200 and (body or {}).get("code") == 1002,
              f"匿名 /warm-flow/published-form 仍 200+code=1002（闸门未放水）：{body}")

        token = login_token()

        # F-2 登录后不再 500 / code=1500
        status, body = get_json("/warm-flow/published-form", {"Authorization": token})
        code = (body or {}).get("code")
        check("F-2", status == 200 and code in (0, 200),
              f"登录 /warm-flow/published-form 不再 500（HTTP {status}, code={code}, body={str(body)[:120]}）")

        # F-3 表结构与 FlowForm 实体逐列对齐
        columns, applied = query_flow_form()
        check("F-3", columns == EXPECTED_COLUMNS,
              f"flow_form 列与实体一致（{len(columns)} 列）：{columns}")
        check("F-3b", set(EXPECTED_COLUMNS).issubset(set(columns)),
              "关键列 form_code/form_content/is_publish/form_type/tenant_id 均在")

        # F-4 迁移已应用
        check("F-4", applied == 1, f"flyway_schema_history 中 V1.2.53 success 记录数={applied}")

        # F-5 相邻链路未被误伤
        status, body = get_json("/warm-flow/query-def", {"Authorization": token})
        check("F-5", status == 200 and (body or {}).get("code") in (0, 200),
              f"登录 /warm-flow/query-def 仍可用（HTTP {status}, code={(body or {}).get('code')}）")

        # F-6 dev 形态不落滚动文件（C4-1 只把 test 纳入，dev 必须仍是纯控制台）
        generated = glob.glob(os.path.join(cwd, "**", "pivotos-admin-server*.log*"), recursive=True)
        check("F-6", not generated, f"dev 形态 cwd 内无日志落盘（C4-1 未误伤 dev）：{generated}")
    finally:
        stop_server(proc, fh)

    passed = sum(1 for _, ok in results if ok)
    print(f"\n==== flow_form 实证：{passed}/{len(results)} PASS ====")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
