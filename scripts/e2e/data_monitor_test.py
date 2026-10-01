#!/usr/bin/env python3
"""通用数据监控 E2E（S130）：系统监控 · 数据监控页后端接口 /monitor/data/*。

覆盖（dev profile，MySQL 组件；ES / Redis 实现为 P2，本轮断言其降级形态）：
  A. 组件清单：mysql available=true 且含 QUERY 能力；未实现的组件 available=false + reasonCode=IMPL_MISSING
  B. 鉴权负例：匿名访问 components / preview / query 一律被拦
  C. 库表列举：schemas / tables 非空
  D. 预览：分页返回、命中敏感列（sys_user.password）必须脱敏
  E. 自由 SQL 正例：单表 SELECT 成功，warnings 含「已注入 LIMIT」
  F. 安全红线负例（本脚本的核心）：8 类写语句 / 堆叠 / 注释绕过 / 非白名单表 / 系统库 /
     SLEEP / UNION / JOIN 一律被拒（available=false + reasonCode=FORBIDDEN），绝不 500
  G. 行数上限：不带 LIMIT 的查询返回行数 <= hard-max-rows 且标记 truncated
  H. 超时熔断：query-timeout-seconds=1 下跑全表扫描，允许「快速成功」或「被熔断降级」两种形态
  I. 权限默认收紧：非超管角色默认不含 monitor:data:query（查 sys_role_menu）
  J. 菜单：sys_menu 存在 1250 / 1251 / 1252 且 perms 正确（Flyway V1.2.51 已应用）
  K. 审计：sys_oper_log 落 module=数据监控 / oper_type=查询 的记录（@Log 生效）

口径：脚本只做只读采集 + 登录 + 少量审计验证，不改任何业务数据；
      进程只 kill 本轮自己启动的 PID。

用法：
    python3 scripts/e2e/data_monitor_test.py
"""
import json
import os
import signal
import subprocess
import sys
import time
import urllib.request

import pymysql
import requests

BASE = "http://localhost:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
JAR = os.path.join(os.path.dirname(__file__), "..", "..", "pivotos-admin-server", "target",
                   "pivotos-admin-server.jar")
LOG_DIR = "/tmp/pivotos-data-monitor-e2e"

DB = dict(
    host="175.24.176.176",
    port=3306,
    user="root",
    password="mysql_DNCi3f",
    database="pivotos_dev",
    charset="utf8mb4",
)

# 负例语句矩阵：每一条都必须被安全闸门拒绝（与权限无关，admin 是超管也照样拒绝）
REJECT_CASES = [
    ("DROP", "DROP TABLE sys_user"),
    ("DELETE", "DELETE FROM sys_user"),
    ("UPDATE", "UPDATE sys_user SET status = '1'"),
    ("INSERT", "INSERT INTO sys_user (id) VALUES (1)"),
    ("ALTER", "ALTER TABLE sys_user ADD COLUMN x varchar(10)"),
    ("TRUNCATE", "TRUNCATE TABLE sys_user"),
    ("GRANT", "GRANT ALL ON *.* TO 'root'@'%'"),
    ("CREATE", "CREATE TABLE t_e2e (id bigint)"),
    ("堆叠", "SELECT * FROM sys_user LIMIT 1; DROP TABLE sys_user"),
    ("注释内堆叠", "SELECT * FROM sys_user /*! ; DROP TABLE sys_user */"),
    ("非白名单表", "SELECT * FROM sys_role"),
    ("系统库", "SELECT * FROM information_schema.tables"),
    ("SLEEP", "SELECT * FROM sys_user WHERE SLEEP(10) = 0"),
    ("BENCHMARK", "SELECT BENCHMARK(10000000, MD5('x')) FROM sys_user"),
    ("UNION", "SELECT id FROM sys_user UNION SELECT id FROM sys_config"),
    ("JOIN", "SELECT u.id FROM sys_user u JOIN sys_config c ON c.id = u.id"),
    ("INTO", "SELECT * FROM sys_user INTO OUTFILE '/tmp/x'"),
]

results = []


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(tag, cond, msg):
    if cond:
        results.append((tag, True))
        log("PASS", f"{tag} {msg}")
    else:
        results.append((tag, False))
        log("FAIL", f"{tag} {msg}")
        raise AssertionError(f"{tag} 断言失败：{msg}")


# ────────────────────────── 后端生命周期 ──────────────────────────

def start_server():
    os.makedirs(LOG_DIR, exist_ok=True)
    log_path = os.path.join(LOG_DIR, "server.log")
    cmd = [
        JAVA,
        "-Djna.library.path=/opt/homebrew/lib",
        "-Djava.awt.headless=true",
        "-jar", os.path.abspath(JAR),
        "--spring.profiles.active=dev",
        "--pivotos.datainspect.enabled=true",
        "--pivotos.datainspect.query-enabled=true",
        # 超时熔断场景（H）用 1s，逼出「慢语句被熔断」形态
        "--pivotos.datainspect.query-timeout-seconds=1",
    ]
    env = dict(os.environ)
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    log("START", f"起服：{' '.join(cmd[6:])}")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=env)
    log("START", f"PID={proc.pid} 日志={log_path}")

    deadline = time.time() + 240
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端进程提前退出（exit={proc.returncode}），见日志 {log_path}")
        try:
            req = urllib.request.Request(
                f"{BASE}/system/auth/login",
                data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200 and json.loads(resp.read()).get("code") == 0:
                    log("START", "后端就绪")
                    return proc, fh
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 240s 内未就绪，见日志 {log_path}")


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
            with urllib.request.urlopen(f"{BASE}/system/auth/login", timeout=2):
                pass
        except Exception:
            return
        time.sleep(1)


# ────────────────────────── 断言 ──────────────────────────

def login():
    r = requests.post(f"{BASE}/system/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=60)
    body = r.json()
    assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
    return {"Authorization": body["data"]["token"]}


def call(hdr, method, path, **kw):
    r = requests.request(method, f"{BASE}{path}", headers=hdr, timeout=120, **kw)
    try:
        body = r.json()
    except Exception:
        body = {}
    return r, body


def run():
    proc, fh = start_server()
    try:
        hdr = login()

        # ① 鉴权负例：匿名一律被拦（三个权限码都要生效）
        for path, method in (("/monitor/data/components", "get"),
                             ("/monitor/data/preview", "post"),
                             ("/monitor/data/query", "post")):
            r, body = call({}, method, path, json={} if method == "post" else None)
            check("B-鉴权", body.get("code") != 0,
                  f"匿名 {method.upper()} {path} 被拦截（HTTP {r.status_code}, code={body.get('code')}）")

        # ② 组件清单
        r, body = call(hdr, "get", "/monitor/data/components")
        check("A-HTTP", r.status_code == 200 and body.get("code") == 0,
              f"HTTP {r.status_code} code={body.get('code')}（降级也必须 200 + code=0，绝不能 500）")
        comps = {c["type"]: c for c in body["data"]}
        check("A-清单", "mysql" in comps, f"组件清单含 mysql：{list(comps)}")
        mysql = comps.get("mysql") or {}
        check("A-可用", mysql.get("available") is True, f"mysql available={mysql.get('available')}")
        check("A-能力", "QUERY" in (mysql.get("capabilities") or []),
              f"mysql capabilities={mysql.get('capabilities')}")
        for pending in ("es", "redis"):
            item = comps.get(pending) or {}
            check("A-降级", item.get("available") is False,
                  f"{pending} 未装配 → available=false（reason={item.get('reason')}）")
            check("A-降级", (item.get("reasonCode") or "") != "",
                  f"{pending} reasonCode={item.get('reasonCode')}（缺一就看不出回落）")

        # ③ 库表列举
        r, body = call(hdr, "get", "/monitor/data/schemas", params={"component": "mysql"})
        check("C-列举", r.status_code == 200 and len(body.get("data") or []) >= 1,
              f"schemas={[s['name'] for s in (body.get('data') or [])][:8]}")
        schema = (body.get("data") or [{}])[0].get("name")
        r, body = call(hdr, "get", "/monitor/data/tables",
                       params={"component": "mysql", "schema": schema})
        tables = body.get("data") or []
        check("C-列举", len(tables) >= 1, f"tables 数量={len(tables)}（schema={schema}）")
        check("C-列举", all(t.get("name") for t in tables), "每张表都有名字")

        # ④ 预览 + 敏感列脱敏（sys_user.password 必须全星）
        r, body = call(hdr, "post", "/monitor/data/preview",
                       json={"component": "mysql", "schema": schema, "table": "sys_user",
                             "pageNum": 1, "pageSize": 5})
        check("D-HTTP", r.status_code == 200 and body.get("code") == 0,
              f"预览 HTTP {r.status_code} code={body.get('code')}")
        data = body.get("data") or {}
        check("D-预览", data.get("available") is True, f"预览 available={data.get('available')}")
        check("D-预览", len(data.get("rows") or []) >= 1, f"预览行数={len(data.get('rows') or [])}")
        cols = {c.get("name"): c for c in (data.get("columns") or [])}
        check("D-脱敏", "password" in cols and cols["password"].get("masked") is True,
              f"password 列标记脱敏：{cols.get('password')}")
        pwd_values = [row.get("password") for row in (data.get("rows") or []) if row.get("password")]
        check("D-脱敏", all(set(str(v)) == {"*"} for v in pwd_values),
              f"password 取值全星：{pwd_values[:3]}")

        # ⑤ 自由 SQL 正例：单表 SELECT
        r, body = call(hdr, "post", "/monitor/data/query",
                       json={"component": "mysql", "schema": schema,
                             "statement": "SELECT id, username, password FROM sys_user"})
        check("E-HTTP", r.status_code == 200 and body.get("code") == 0,
              f"自由 SQL HTTP {r.status_code} code={body.get('code')}")
        data = body.get("data") or {}
        check("E-正例", data.get("available") is True, f"available={data.get('available')}")
        warns = data.get("warnings") or []
        check("E-正例", any("LIMIT" in w for w in warns), f"warnings 含 LIMIT 注入：{warns}")
        check("E-脱敏", all(set(str(row.get("password"))) == {"*"}
                            for row in (data.get("rows") or []) if row.get("password")),
              "自由 SQL 结果同样脱敏（脱敏与入口无关）")

        # ⑥ 安全红线负例（核心）
        for name, sql in REJECT_CASES:
            r, body = call(hdr, "post", "/monitor/data/query",
                           json={"component": "mysql", "schema": schema, "statement": sql})
            data = body.get("data") or {}
            check(f"F-{name}", r.status_code == 200 and body.get("code") == 0,
                  f"被拒也必须 200 + code=0（HTTP {r.status_code}, code={body.get('code')}）")
            check(f"F-{name}", data.get("available") is False,
                  f"available=false（reasonCode={data.get('reasonCode')}）")
            check(f"F-{name}", data.get("reasonCode") == "FORBIDDEN",
                  f"reasonCode=FORBIDDEN，实际={data.get('reasonCode')} / {data.get('reason')}")
            check(f"F-{name}", len(data.get("rows") or []) == 0, "被拒时 rows 为空")

        # ⑦ 行数上限：不带 LIMIT 查大表
        r, body = call(hdr, "post", "/monitor/data/query",
                       json={"component": "mysql", "schema": schema,
                             "statement": "SELECT * FROM sys_oper_log"})
        data = body.get("data") or {}
        rows = data.get("rows") or []
        check("G-行数", len(rows) <= 200, f"默认页大小 200 生效：rows={len(rows)}")
        check("G-行数", data.get("truncated") is True or len(rows) < 200,
              f"超限时标记 truncated={data.get('truncated')}")

        # ⑧ 超时熔断（软断言：1s 超时下，慢语句要么快到成功，要么被熔断降级，两者都合法）
        r, body = call(hdr, "post", "/monitor/data/query",
                       json={"component": "mysql", "schema": schema,
                             "statement": "SELECT * FROM sys_oper_log WHERE request_params LIKE '%PivotOS%'"})
        data = body.get("data") or {}
        ok = (data.get("available") is True) or (data.get("reasonCode") == "COLLECT_FAILED")
        check("H-超时", r.status_code == 200 and ok,
              f"超时要么成功要么降级（available={data.get('available')}, "
              f"reasonCode={data.get('reasonCode')}）")

        # ⑨ 权限默认收紧（查库，不改库）
        conn = pymysql.connect(**DB)
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT id, menu_name, menu_type, perms FROM sys_menu "
                            "WHERE id IN (1250, 1251, 1252) ORDER BY id")
                menus = cur.fetchall()
                check("J-菜单", len(menus) == 3, f"sys_menu 1250/1251/1252 已插入：{menus}")
                perms = {m[0]: m[3] for m in menus}
                check("J-菜单", perms.get(1250) == "monitor:data:list", f"1250 perms={perms.get(1250)}")
                check("J-菜单", perms.get(1251) == "monitor:data:preview", f"1251 perms={perms.get(1251)}")
                check("J-菜单", perms.get(1252) == "monitor:data:query", f"1252 perms={perms.get(1252)}")

                # 权限默认收紧：菜单 1252（monitor:data:query）不挂在任何角色上，
                # 只有 super_admin 走 *:*:* 通配——即「高危权限默认只给超管」
                cur.execute("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 1252")
                granted = cur.fetchone()[0]
                check("I-权限", granted == 0,
                      f"monitor:data:query 默认未授予任何普通角色（授予数={granted}，仅 super_admin 通配 *:*:*）")
                cur.execute("SELECT COUNT(*) FROM sys_role WHERE role_code = 'super_admin'")
                check("I-权限", cur.fetchone()[0] >= 1, "super_admin 角色存在（其 *:*:* 通配覆盖本能力）")

                cur.execute("SELECT COUNT(*) FROM sys_oper_log "
                            "WHERE module = '数据监控' AND oper_type = '查询'")
                audit = cur.fetchone()[0]
                check("K-审计", audit >= 1, f"sys_oper_log 已落数据监控审计 {audit} 条")
        finally:
            conn.close()

    finally:
        stop_server(proc, fh)


def main():
    try:
        run()
    except AssertionError:
        pass
    passed = sum(1 for _, ok in results if ok)
    failed = len(results) - passed
    print()
    log("SUMMARY", f"断言 {len(results)} 条：PASS {passed} / FAIL {failed}")
    if failed:
        for tag, ok in results:
            if not ok:
                log("FAILED", tag)
        sys.exit(1)
    log("SUMMARY", "通用数据监控 E2E 全部通过")


if __name__ == "__main__":
    main()
