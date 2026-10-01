#!/usr/bin/env python3
"""通用数据监控 E2E（S130 / S130-P2）：系统监控 · 数据监控页后端接口 /monitor/data/*。

覆盖（dev profile，三组件）：
  A. 组件清单：mysql / redis / es 均可用；**Redis 与 ES 无 QUERY 能力**（无等价语句级闸门 →
     只暴露结构化浏览）；扩展点占位（neo4j 等）available=false + reasonCode=IMPL_MISSING
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
  L. Redis（P2）：db 清单 → SCAN 列举 key（pattern 收窄生效）→ 按 TYPE 取值 → 敏感 key 脱敏 →
     统计；自由查询一律 UNSUPPORTED（不开放命令入口）
  M. ES（P2）：indices 分组 → 索引清单（排除系统内建索引）→ _search 预览 → 统计；
     自由 DSL 一律 UNSUPPORTED
  N. ES 双版本（设计 §9-H）：9200（ES 9.5.3，compatibility-mode=false）与
     9201（ES 7.17.28，compatibility-mode=true）各跑一遍 M
  O. 不可达降级（设计 §9-G）：ES 指向不可达地址 → 一律 200 + code=0 + available=false + reason，
     绝不 500；越权/未装配组件（neo4j）→ IMPL_MISSING；非法组件名 → 业务码非 0

口径：脚本只做只读采集 + 登录 + 少量审计验证，不改任何业务数据；
      进程只 kill 本轮自己启动的 PID（三个后端实例依次起停）。

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

# ES 双目标（与 S127 / S127-B 同口径）：9200 = ES 9.5.3，9201 = ES 7.17.28（必须 compatibility-mode=true）
# ⚠️ 两个目标的口令不同：只覆盖 uris + compatibility-mode 会拿 9200 的口令去连 9201 → 401
#    「unable to authenticate user [elastic]」，表象是「版本探测 unknown / 组件不可达」，极易误判成网络问题。
ES_TARGETS = [
    ("9200-9.5.3", "http://175.24.176.176:9200", "false", "elastic", "Elastic_RGRSPM"),
    ("9201-7.17.28", "http://175.24.176.176:9201", "true", "elastic", "Elastic_P7SSAJ"),
]
# 不可达目标（用于降级断言）
ES_UNREACHABLE = "http://10.0.0.7:9200"

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

def start_server(extra_args=(), tag="main"):
    os.makedirs(LOG_DIR, exist_ok=True)
    log_path = os.path.join(LOG_DIR, f"server-{tag}.log")
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
        *extra_args,
    ]
    env = dict(os.environ)
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    log("START", f"起服[{tag}]：{' '.join(cmd[6:])}")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=env)
    log("START", f"PID={proc.pid} 日志={log_path}")

    deadline = time.time() + 300
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
                    log("START", f"后端就绪[{tag}]")
                    return proc, fh
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 300s 内未就绪，见日志 {log_path}")


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


def assert_es_component(hdr, tag_prefix, expect_available):
    """ES 组件断言（双目标共用）：indices 分组 → 索引清单 → _search 预览 → 统计 → 自由 DSL 被拒。
    期望可用时跑完整链路；期望不可用时只校验降级形态（绝不 500）。"""
    r, body = call(hdr, "get", "/monitor/data/components")
    check(f"{tag_prefix}-HTTP", r.status_code == 200 and body.get("code") == 0,
          f"components HTTP {r.status_code} code={body.get('code')}（降级也必须 200 + code=0）")
    comps = {c["type"]: c for c in body["data"]}
    es = comps.get("es") or {}
    check(f"{tag_prefix}-可用", es.get("available") is expect_available,
          f"es available={es.get('available')}（期望 {expect_available}）reason={es.get('reason')}")
    check(f"{tag_prefix}-能力", "QUERY" not in (es.get("capabilities") or []),
          f"ES 不开放自由 DSL：capabilities={es.get('capabilities')}")

    if not expect_available:
        check(f"{tag_prefix}-降级", (es.get("reasonCode") or "") != "",
              f"不可用时必须给 reasonCode（缺一就看不出回落）：{es.get('reasonCode')}")
        r, body = call(hdr, "get", "/monitor/data/schemas", params={"component": "es"})
        check(f"{tag_prefix}-降级", r.status_code == 200 and len(body.get("data") or []) == 0,
              f"不可用时 schemas 返回空列表且不报错（HTTP {r.status_code}）")
        r, body = call(hdr, "post", "/monitor/data/preview",
                       json={"component": "es", "schema": "indices", "table": "sys-oper-log",
                             "pageNum": 1, "pageSize": 5})
        data = body.get("data") or {}
        check(f"{tag_prefix}-降级", r.status_code == 200 and data.get("available") is False,
              f"不可用时预览 available=false（HTTP {r.status_code}）")
        return

    # ① indices 分组
    r, body = call(hdr, "get", "/monitor/data/schemas", params={"component": "es"})
    schemas = body.get("data") or []
    check(f"{tag_prefix}-列举", r.status_code == 200 and any(s["name"] == "indices" for s in schemas),
          f"schemas={[s['name'] for s in schemas]}")

    # ② 索引清单（系统内建索引必须排除，否则 7.x 与 9.x 不可比）
    r, body = call(hdr, "get", "/monitor/data/tables",
                   params={"component": "es", "schema": "indices"})
    tables = body.get("data") or []
    check(f"{tag_prefix}-列举", len(tables) >= 1, f"索引数={len(tables)}：{[t['name'] for t in tables][:8]}")
    check(f"{tag_prefix}-列举", all(not t["name"].startswith(".") for t in tables),
          "系统内建索引（. 开头）必须排除")
    check(f"{tag_prefix}-列举", all(t.get("type") == "index" for t in tables), "索引节点 type=index")

    # ③ _search 预览
    index = tables[0]["name"]
    r, body = call(hdr, "post", "/monitor/data/preview",
                   json={"component": "es", "schema": "indices", "table": index,
                         "pageNum": 1, "pageSize": 5})
    check(f"{tag_prefix}-预览", r.status_code == 200 and body.get("code") == 0,
          f"预览 HTTP {r.status_code} code={body.get('code')}")
    data = body.get("data") or {}
    check(f"{tag_prefix}-预览", data.get("available") is True,
          f"available={data.get('available')} reason={data.get('reason')}")
    check(f"{tag_prefix}-预览", len(data.get("columns") or []) >= 1,
          f"列数={len(data.get('columns') or [])}：{[c['name'] for c in (data.get('columns') or [])][:8]}")
    check(f"{tag_prefix}-预览", len(data.get("rows") or []) >= 1,
          f"行数={len(data.get('rows') or [])}（index={index}）")
    check(f"{tag_prefix}-预览", int(data.get("total") or 0) >= len(data.get("rows") or []),
          f"total={data.get('total')} 与行数自洽（track_total_hits 生效）")

    # ④ 非法索引名必须被拒（防 URL 注入到别的端点）
    r, body = call(hdr, "post", "/monitor/data/preview",
                   json={"component": "es", "schema": "indices", "table": "a,b",
                         "pageNum": 1, "pageSize": 5})
    data = body.get("data") or {}
    check(f"{tag_prefix}-闸门", r.status_code == 200 and data.get("available") is False,
          f"含逗号的索引名被拒（reasonCode={data.get('reasonCode')}）")

    # ⑤ 统计
    r, body = call(hdr, "get", "/monitor/data/stats",
                   params={"component": "es", "schema": "indices", "table": index})
    stats = body.get("data") or {}
    check(f"{tag_prefix}-统计", r.status_code == 200 and int(stats.get("rowCount") or -1) >= 0,
          f"rowCount={stats.get('rowCount')} sizeBytes={stats.get('sizeBytes')} engine={stats.get('engine')}")

    # ⑥ 自由 DSL 不开放（即便超管也只给 UNSUPPORTED）
    r, body = call(hdr, "post", "/monitor/data/query",
                   json={"component": "es", "schema": "indices",
                         "statement": "{\"query\":{\"match_all\":{}}}"})
    data = body.get("data") or {}
    check(f"{tag_prefix}-DSL", r.status_code == 200 and data.get("available") is False
          and data.get("reasonCode") == "UNSUPPORTED",
          f"自由 DSL 被拒（reasonCode={data.get('reasonCode')}）")


def assert_redis(hdr):
    """Redis 组件（P2）：三重保护 + TYPE 感知取值 + 敏感 key 脱敏 + 不开放命令入口。"""
    # ① 组件能力：有 LIST_TABLES/PREVIEW/STATS，无 QUERY
    r, body = call(hdr, "get", "/monitor/data/components")
    comps = {c["type"]: c for c in body["data"]}
    redis = comps.get("redis") or {}
    check("L-可用", redis.get("available") is True, f"redis available={redis.get('available')}")
    caps = redis.get("capabilities") or []
    check("L-能力", all(c in caps for c in ("LIST_SCHEMAS", "LIST_TABLES", "PREVIEW", "STATS")),
          f"capabilities={caps}")
    check("L-能力", "QUERY" not in caps, f"Redis 无 QUERY 能力（不开放命令入口）：{caps}")
    check("L-保护", "SCAN" in (redis.get("detail") or ""),
          f"后端必须回传 SCAN 保护口径：{redis.get('detail')}")

    # ② db 清单
    r, body = call(hdr, "get", "/monitor/data/schemas", params={"component": "redis"})
    schemas = body.get("data") or []
    check("L-列举", r.status_code == 200 and len(schemas) == 1, f"schemas={[s['name'] for s in schemas]}")
    db = schemas[0]["name"]
    check("L-列举", db.startswith("db"), f"库名形态为 dbN：{db}")
    check("L-列举", int(schemas[0]["itemCount"]) >= 1, f"key 总数={schemas[0]['itemCount']}")

    # ③ SCAN 列举 key（默认 pattern）
    r, body = call(hdr, "get", "/monitor/data/tables", params={"component": "redis", "schema": db})
    keys = body.get("data") or []
    check("L-列举", len(keys) >= 1, f"key 数={len(keys)}")
    check("L-列举", all(k.get("type") for k in keys),
          f"每个 key 都带类型：{[k.get('type') for k in keys][:5]}")

    # ④ pattern 收窄生效（三重保护下唯一有效的办法）
    r, body = call(hdr, "get", "/monitor/data/tables",
                   params={"component": "redis", "schema": db, "pattern": "Authorization:sys-user:token:*"})
    narrowed = body.get("data") or []
    check("L-pattern", len(narrowed) >= 1 and len(narrowed) <= len(keys),
          f"pattern 收窄后 key 数={len(narrowed)}（全量 {len(keys)}）")
    check("L-pattern", all("token" in k["name"] for k in narrowed),
          "收窄结果全部命中 pattern（pattern 真的透传到 SCAN）")

    # ⑤ 敏感 key 的 value 必须脱敏（key 名含 token → 列名 value 本身不命中）
    token_key = narrowed[0]["name"]
    r, body = call(hdr, "post", "/monitor/data/preview",
                   json={"component": "redis", "schema": db, "table": token_key,
                         "pageNum": 1, "pageSize": 5})
    check("L-预览", r.status_code == 200 and body.get("code") == 0,
          f"预览 HTTP {r.status_code} code={body.get('code')}")
    data = body.get("data") or {}
    check("L-预览", data.get("available") is True, f"available={data.get('available')}")
    row = (data.get("rows") or [{}])[0]
    cols = {c.get("name"): c for c in (data.get("columns") or [])}
    check("L-脱敏", (cols.get("value") or {}).get("masked") is True,
          f"value 列标记脱敏：{cols.get('value')}")
    check("L-脱敏", set(str(row.get("value"))) == {"*"} and row.get("value"),
          f"token key 的取值全星：{str(row.get('value'))[:12]}…（key={token_key[:40]}）")

    # ⑥ 非敏感 key 不脱敏（证明脱敏是规则驱动，不是一刀切）
    others = [k for k in keys if "token" not in k["name"]]
    if others:
        r, body = call(hdr, "post", "/monitor/data/preview",
                       json={"component": "redis", "schema": db, "table": others[0]["name"],
                             "pageNum": 1, "pageSize": 5})
        data = body.get("data") or {}
        plain_row = (data.get("rows") or [{}])[0]
        check("L-脱敏", str(plain_row.get("value")) != "" and set(str(plain_row.get("value"))) != {"*"},
              f"非敏感 key 不脱敏（key={others[0]['name'][:40]}）")
    else:
        log("SKIP", "Redis 中无「非 token」key，跳过「不脱敏」反例")

    # ⑦ 统计
    r, body = call(hdr, "get", "/monitor/data/stats",
                   params={"component": "redis", "schema": db, "table": token_key})
    stats = body.get("data") or {}
    check("L-统计", r.status_code == 200 and stats.get("engine") == "string",
          f"engine={stats.get('engine')} rowCount={stats.get('rowCount')} extra={stats.get('extra')}")

    # ⑧ 自由查询入口不开放（写命令更不可能有入口）
    r, body = call(hdr, "post", "/monitor/data/query",
                   json={"component": "redis", "schema": db, "statement": "FLUSHALL"})
    data = body.get("data") or {}
    check("L-入口", r.status_code == 200 and data.get("available") is False
          and data.get("reasonCode") == "UNSUPPORTED",
          f"Redis 自由查询被拒（reasonCode={data.get('reasonCode')}）")
    check("L-入口", len(data.get("rows") or []) == 0, "被拒时 rows 为空")


def run_main():
    proc, fh = start_server(tag="main")
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

        # 三组件齐活（P2）：Redis / ES 均可用，且都无 QUERY 能力
        for name in ("redis", "es"):
            item = comps.get(name) or {}
            check(f"A-{name}", item.get("available") is True,
                  f"{name} available={item.get('available')} reason={item.get('reason')}")
            check(f"A-{name}", "QUERY" not in (item.get("capabilities") or []),
                  f"{name} 无 QUERY 能力（无等价语句级闸门 → 只暴露结构化浏览）")

        # 扩展点占位：支持但未接入的组件必须「可见且明确不可用」
        for pending in ("neo4j", "clickhouse", "mongodb"):
            item = comps.get(pending) or {}
            check("O-占位", item.get("available") is False and item.get("reasonCode") == "IMPL_MISSING",
                  f"{pending} 占位：available={item.get('available')} reasonCode={item.get('reasonCode')}")

        # 非法组件名：业务码非 0（不是降级，是参数错误）
        r, body = call(hdr, "get", "/monitor/data/schemas", params={"component": "not-exist"})
        check("O-越权", r.status_code == 200 and body.get("code") != 0,
              f"非法组件名被拒（HTTP {r.status_code}, code={body.get('code')}）")

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

        # ⑨ Redis 组件（P2）
        assert_redis(hdr)

        # ⑩ ES 组件（P2，本轮第一个目标：dev 默认的 9200 / ES 9.5.3）
        assert_es_component(hdr, "M", True)

        # ⑪ 权限默认收紧 + 菜单 + 审计（查库，不改库）
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


def run_es_target(name, uris, compatibility_mode, username, password):
    """设计 §9-H：ES 双版本各跑一遍（9200 9.5.3 / 9201 7.17.28 + compatibility-mode=true）。"""
    args = [f"--pivotos.search.es-java.uris={uris}",
            f"--pivotos.search.es-java.compatibility-mode={compatibility_mode}",
            f"--pivotos.search.es-java.username={username}",
            f"--pivotos.search.es-java.password={password}"]
    proc, fh = start_server(extra_args=args, tag=f"es-{name}")
    try:
        hdr = login()
        assert_es_component(hdr, f"N-{name}", True)
    finally:
        stop_server(proc, fh)


def run_es_unreachable():
    """设计 §9-G：ES 不可达 → 200 + code=0 + available=false + reason，绝不 500。"""
    args = [f"--pivotos.search.es-java.uris={ES_UNREACHABLE}"]
    proc, fh = start_server(extra_args=args, tag="es-down")
    try:
        hdr = login()
        assert_es_component(hdr, "O-降级", False)
        # MySQL 不受影响：别的组件不能因为 ES 挂了而一起不可用
        r, body = call(hdr, "get", "/monitor/data/components")
        comps = {c["type"]: c for c in body["data"]}
        check("O-降级", comps.get("mysql", {}).get("available") is True,
              "ES 不可达时 MySQL 仍可用（组件降级互不影响）")
        check("O-降级", comps.get("redis", {}).get("available") is True,
              "ES 不可达时 Redis 仍可用")
    finally:
        stop_server(proc, fh)


def main():
    # --only=main|es|down|all：排障时只跑一段，避免每次都等三个后端实例
    only = "all"
    for arg in sys.argv[1:]:
        if arg.startswith("--only="):
            only = arg.split("=", 1)[1].strip()
    try:
        if only in ("all", "main"):
            run_main()
        if only in ("all", "es"):
            for name, uris, compat, user, pwd in ES_TARGETS:
                run_es_target(name, uris, compat, user, pwd)
        if only in ("all", "down"):
            run_es_unreachable()
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
    log("SUMMARY", "通用数据监控 E2E 全部通过（含 Redis / ES 双目标）")


if __name__ == "__main__":
    main()
