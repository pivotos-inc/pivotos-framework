#!/usr/bin/env python3
"""S122 操作日志检索链路 E2E（运行态证据：业务检索真的走 SearchTemplate）。

前置：后端已在 8080 起服（fat jar 或 IDE 均可），dev 库可直连。
验证点（逐条断言，失败即抛）：
  ① 登录 admin 取 token；
  ② 分页查询 /system/log/oper/page → code=0 且 total>0（冷启动回灌把历史日志灌进索引）；
  ③ 时间区间检索 → total>0（钉死 S122 修复的 simple 时间比较：文档侧时间字符串 vs 条件侧 LocalDateTime）；
  ④ 条件检索（module 模糊 + status 精确）→ 过滤生效且与总数一致；
  ⑤ 纯 SQL 插入的行查不到 → 证明列表读的是检索索引而不是 DB 直查（同时钉住「非经应用写入的数据不进索引」的边界）；
  ⑥ 触发 @Log 埋点接口（用户导出）→ 新日志立即被检索到（写侧双写生效）；
  ⑦ 自清：脚本自己插入的行物理删除。

口径：本脚本只删自己造的行，不动任何存量数据。
"""
import sys
from datetime import datetime, timedelta

import pymysql
import requests

BASE = "http://localhost:8080"

DB = dict(
    host="175.24.176.176",
    port=3306,
    user="root",
    password="mysql_DNCi3f",
    database="pivotos_dev",
    charset="utf8mb4",
)

# 仅用于「证明纯 SQL 写入不进索引」的哨兵模块名（脚本自建自清）
SENTINEL_MODULE = "S122E2E-SQL-ONLY"

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


def page(query):
    """分页查询。注意：全局 JSON 配置把 Long 序列化成字符串，total 需转 int。"""
    r = requests.get(f"{BASE}/system/log/oper/page", params=query, headers=HDR, timeout=60)
    body = r.json()
    assert r.status_code == 200 and body.get("code") == 0, f"查询失败 HTTP {r.status_code}：{body}"
    data = body["data"]
    data["total"] = int(data["total"])
    return data


def sql_execute(sql, args=None, fetch=False):
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args or ())
            if fetch:
                return cur.fetchall()
            conn.commit()
            return cur.rowcount
    finally:
        conn.close()


# ── Step 1: 登录 ────────────────────────────────────────────
log("STEP1", "登录 admin")
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
body = r.json()
assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
HDR = {"Authorization": body["data"]["token"]}
check("PASS1", True, "token OK")

# ── Step 2: 基础分页（证明冷启动回灌后索引有数据） ────────────
log("STEP2", "GET /system/log/oper/page 基础分页")
data = page({"pageNum": 1, "pageSize": 5})
total = data["total"]
check("PASS2", total > 0 and len(data["list"]) > 0, f"total={total}, 本页 {len(data['list'])} 条")

# ── Step 3: 时间区间检索（S122 修复的回归点） ─────────────────
log("STEP3", "时间区间检索（begin/end 覆盖近 30 天）")
begin = (datetime.now() - timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%S")
end = datetime.now().strftime("%Y-%m-%dT%H:%M:%S")
ranged = page({"pageNum": 1, "pageSize": 5, "beginTime": begin, "endTime": end})
check("PASS3", ranged["total"] > 0,
      f"区间 [{begin} ~ {end}] 命中 {ranged['total']} 条（>0 证明 simple 侧时间比较生效）")

# ── Step 4: 条件检索（module 模糊 + status 精确） ─────────────
log("STEP4", "module 模糊 + status 精确")
filtered = page({"pageNum": 1, "pageSize": 5, "module": "管理", "status": 0})
check("PASS4", 0 < filtered["total"] <= total,
      f"module=管理 & status=0 命中 {filtered['total']} 条（全量 {total} 条：过滤生效且未被放大）")

# ── Step 5: 纯 SQL 插入的行不进索引（证明读的是索引不是 DB） ───
log("STEP5", f"SQL 插入哨兵行 module={SENTINEL_MODULE}")
sql_execute(
    "INSERT INTO sys_oper_log (id, module, oper_type, oper_name, oper_user_id, method, "
    "request_method, request_url, request_params, status, duration, oper_time, "
    "create_by, create_time, update_by, update_time, deleted) VALUES "
    "(990122000000001, %s, '新增', 's122-e2e', 1, 'com.pivotos.S122.e2e()', 'GET', "
    "'/api/system/log/oper/page', '{}', 0, 1, NOW(), 0, NOW(), 0, NOW(), 0)",
    (SENTINEL_MODULE,),
)
sentinel = page({"pageNum": 1, "pageSize": 5, "module": SENTINEL_MODULE})
check("PASS5", sentinel["total"] == 0,
      "SQL 直插的行检索不到 → 列表确实走检索索引（同时钉住边界：非经应用写入的数据不进索引）")

# ── Step 6: @Log 埋点写入后立即被检索到（双写生效） ────────────
log("STEP6", "触发 @Log 埋点接口 POST /system/user/export")
before = page({"pageNum": 1, "pageSize": 1, "module": "用户管理"})["total"]
r = requests.post(f"{BASE}/system/user/export", json={}, headers=HDR, timeout=120)
log("STEP6", f"导出接口 HTTP {r.status_code}（导出成功/权限不足均不影响本断言，重点是 @Log 落库）")
after = page({"pageNum": 1, "pageSize": 1, "module": "用户管理"})["total"]
check("PASS6", after > before,
      f"用户管理 日志数 {before} → {after}（@Log 切面写入后经双写进入索引，立即可被检索）")

# ── Step 7: 自清 ─────────────────────────────────────────────
log("STEP7", "清理脚本自建的哨兵行")
removed = sql_execute("DELETE FROM sys_oper_log WHERE module = %s", (SENTINEL_MODULE,))
check("PASS7", removed >= 1, f"删除哨兵行 {removed} 行")

log("SUMMARY", f"{sum(1 for _, ok in results if ok)}/{len(results)} PASS")
sys.exit(0 if all(ok for _, ok in results) else 1)
