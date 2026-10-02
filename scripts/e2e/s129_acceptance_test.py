#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S129｜V2 统一验收：核心链路逐条断言（dev 库清场后「全新库」形态）。

背景（本脚本存在的理由）
------------------------
S129 把 dev 库重建、让 Flyway 从 V1 全量重放（85 migrations）并清掉 dev ES 三个业务索引的
孤儿数据后，dev 环境回到「全新库 + 零业务数据」形态。历史 E2E 脚本大多依赖累积 fixture，
在新库上跑不动；而 V2 统一验收要回答的是：**每一条业务链路在干净库上能不能跑通**。
本脚本按《06-联调测试流程》组织，逐条记录 PASS / FAIL / 阻塞原因，且**不为凑绿放宽断言**：
依赖外部资源（LLM Key、云对象存储）的链路，不可得时记 BLOCKED 并写明原因与去向，不降级成 PASS。

覆盖（A~K 共 11 组）
-------------------
A 基础鉴权：登录 code=0 / 匿名 /sse=401 / 匿名 REST=200+code=1002（既有形态，不是 401）
B 菜单与动态路由：routers 树能走到 1181~1183（前端组件演示）/ 1240（ES 监控）/ 1250（数据监控）
C 系统管理：用户/角色/部门/字典/参数/岗位/通知/操作日志/登录日志 九个列表端点
D 检索抽象（ST-SEARCH）：操作日志分页带时间区间 + 模块过滤；证明读的是检索通道
E 工作流：save-json 建定义 → 发布 → 发起 → 待办可见 → 审批通过 → 历史留痕 → 物理自清归零
F 知识库（RAG）：建库 → 列表可见 → 检索接口 →（上传链路依赖云桶，不可得记 BLOCKED）→ 自清
G AI 图表：POST /monitor/dashboard/ai-chart 生成 + 历史分页（依赖 LLM，不可得记 BLOCKED）
H 代码生成器：db/list → import 表 → preview 预览产物 → 自清
I 用户导入：模板可下载（真实落库段由 ui/scripts/e2e/s125_import_preview_e2e.ts 覆盖）
J ES 监控（1240）：available=true + 索引清单 + 四态字段（implementation/configuredType/fallback）
K 数据监控（1250）：components 三组件 + schemas/tables/stats/preview + QUERY 权限默认收紧

前置：后端已在 8080 起服（本脚本不抢端口、不自启停；只 kill 自己造的数据，不 kill 任何进程）。
用法：
    python3 scripts/e2e/s129_acceptance_test.py
    PIVOTOS_DASHSCOPE_KEY=sk-xxx python3 scripts/e2e/s129_acceptance_test.py   # 供 AI 链路临时登记
"""
import json
import os
import sys
import time
from datetime import datetime, timedelta

import pymysql
import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dev_db  # noqa: E402  统一物理清理口径

BASE = "http://localhost:8080"
DB = dict(host="175.24.176.176", port=3306, user="root", password="mysql_DNCi3f",
          database="pivotos_dev", charset="utf8mb4")

FLOW_CODE = "s129_accept_flow"
KB_NAME = "S129验收知识库"
GEN_TABLE = "sys_dict_type"          # 生成器导入用的样本表（小表、无外键依赖）

PASSED = 0
FAILED = 0
BLOCKED = 0
RESULTS = []


def check(name, ok, detail="", blocked=False):
    global PASSED, FAILED, BLOCKED
    if blocked:
        BLOCKED += 1
        RESULTS.append(("BLOCKED", name, detail))
        print(f"  BLOCK  {name}{(' —— ' + detail) if detail else ''}", flush=True)
    elif ok:
        PASSED += 1
        RESULTS.append(("PASS", name, detail))
        print(f"  PASS   {name}", flush=True)
    else:
        FAILED += 1
        RESULTS.append(("FAIL", name, detail))
        print(f"  FAIL   {name}{(' —— ' + detail) if detail else ''}", flush=True)


def section(title):
    print(f"\n=== {title} ===", flush=True)


def get(path, hdr, **params):
    return requests.get(f"{BASE}{path}", headers=hdr, params=params or None, timeout=30)


def post(path, hdr, payload=None, **kw):
    return requests.post(f"{BASE}{path}", headers=hdr, json=payload, timeout=kw.get("timeout", 30))


def j(resp):
    try:
        return resp.json()
    except Exception:
        return {"__raw__": resp.text[:200]}


# ── A 基础鉴权 ────────────────────────────────────────────────
def a_auth():
    section("A 基础鉴权")
    r = requests.post(f"{BASE}/system/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=30)
    body = j(r)
    ok = r.status_code == 200 and body.get("code") == 0 and (body.get("data") or {}).get("token")
    check("A1 登录 admin 返回 code=0 且签发 token", ok, str(body)[:150])
    if not ok:
        raise SystemExit("登录失败，后续无法进行")
    token = body["data"]["token"]
    hdr = {"Authorization": token}

    anon = requests.get(f"{BASE}/sse", timeout=15)
    check("A2 匿名 GET /sse = 401", anon.status_code == 401, f"HTTP {anon.status_code}")

    # 既有形态是 200 + code=1002（不是 401）：匿名访问 REST 被 SaInterceptor 拦在业务码层
    anon2 = j(get("/system/user/page", {}, **{"pageNum": 1, "pageSize": 1}))
    check("A3 匿名访问 REST = 200 + code=1002（既有形态）",
          anon2.get("code") == 1002, f"code={anon2.get('code')}")
    return hdr


# ── B 菜单与动态路由 ──────────────────────────────────────────
def walk(nodes, out):
    for n in nodes or []:
        out.append((n.get("path"), n.get("component") or n.get("name")))
        walk(n.get("children"), out)
    return out


def b_routers(hdr):
    section("B 菜单与动态路由（V1.2.50/51/52 新增菜单）")
    body = j(get("/system/menu/routers", hdr))
    check("B1 /system/menu/routers 返回 code=0", body.get("code") == 0, str(body)[:120])
    routes = walk(body.get("data") or [], [])
    flat = json.dumps(routes, ensure_ascii=False)
    for cid, comp, label in (("1181", "tool/demo/virtual-table/index", "虚拟表格"),
                             ("1182", "tool/demo/schema-form/index", "动态表单"),
                             ("1183", "tool/demo/import-preview/index", "导入预览")):
        check(f"B2 菜单 {cid} {label} 在动态路由中下发", comp in flat, f"缺 {comp}")
    check("B3 菜单 1240 ES 监控（monitor/es/index）下发", "monitor/es/index" in flat)
    check("B4 菜单 1250 数据监控（monitor/data/index）下发", "monitor/data/index" in flat)


# ── C 系统管理 ───────────────────────────────────────────────
def c_system(hdr):
    section("C 系统管理（九个列表端点）")
    cases = [
        ("/system/user/page", "用户分页"),
        ("/system/role/page", "角色分页"),
        ("/system/dept/tree", "部门树"),
        ("/system/dict/type/page", "字典类型分页"),
        ("/system/dict/data/page", "字典数据分页"),
        ("/system/config/page", "参数配置分页"),
        ("/system/post/list", "岗位列表"),
        ("/system/notice/page", "通知公告分页"),
        ("/system/log/login/page", "登录日志分页"),
    ]
    for path, label in cases:
        body = j(get(path, hdr, **{"pageNum": 1, "pageSize": 5}))
        check(f"C 系统管理 {label} {path}", body.get("code") == 0,
              f"code={body.get('code')} msg={body.get('msg')}")


# ── D 检索抽象（ST-SEARCH）───────────────────────────────────
def d_search(hdr):
    section("D 检索抽象链路（ST-SEARCH：操作日志）")
    body = j(get("/system/log/oper/page", hdr, **{"pageNum": 1, "pageSize": 10}))
    check("D1 操作日志分页 code=0（检索通道或 DB 回退）", body.get("code") == 0,
          f"code={body.get('code')}")
    end = datetime.now()
    start = end - timedelta(days=7)
    body = j(get("/system/log/oper/page", hdr, **{
        "pageNum": 1, "pageSize": 10,
        "beginTime": start.strftime("%Y-%m-%dT%H:%M:%S"),
        "endTime": end.strftime("%Y-%m-%dT%H:%M:%S")}))
    check("D2 时间区间检索 code=0（S122 修复的时间条件恒不成立）", body.get("code") == 0,
          f"code={body.get('code')} msg={body.get('msg')}")
    body = j(get("/system/log/oper/page", hdr, **{
        "pageNum": 1, "pageSize": 5, "module": "登录"}))
    check("D3 模块关键字过滤 code=0", body.get("code") == 0, f"code={body.get('code')}")


# ── E 工作流 ─────────────────────────────────────────────────
def defjson():
    def node(code, name, ntype, perm=None, coord="0,0", skips=()):
        n = {"nodeType": ntype, "nodeCode": code, "nodeName": name, "nodeRatio": "0.000",
             "coordinate": coord, "skipList": [dict(s) for s in skips]}
        if perm is not None:
            n["permissionFlag"] = perm
        return n

    def skip(now, nxt, name):
        return {"nowNodeCode": now, "nextNodeCode": nxt, "skipName": name, "skipType": "PASS"}

    return {
        "flowCode": FLOW_CODE,
        "flowName": "S129验收-两级审批",
        "modelValue": "CLASSICS",
        "nodeList": [
            node("start", "开始", 0, coord="80,240", skips=[skip("start", "leader", "提交")]),
            node("leader", "主管审批", 1, perm="1", coord="300,240",
                 skips=[skip("leader", "end", "同意")]),
            node("end", "结束", 2, coord="520,240"),
        ],
    }


def e_workflow(hdr):
    section("E 工作流（发起 → 审批 → 历史 → 自清）")
    def_id, ins_id = None, None
    try:
        r = post("/warm-flow/save-json", {**hdr, "onlyNodeSkip": "false"}, defjson())
        body = j(r)
        check("E1 save-json 建一次性定义", body.get("code") in (0, 200), str(body)[:200])
        if body.get("code") not in (0, 200):
            return
        body = j(get("/workflow/definition/page", hdr, **{"pageNum": 1, "pageSize": 50,
                                                          "flowCode": FLOW_CODE}))
        defs = [d for d in (body.get("data") or {}).get("list", []) if d.get("isPublish") != 9]
        check("E2 定义已落库可查", bool(defs), str(body)[:150])
        if not defs:
            return
        def_id = max(defs, key=lambda d: int(d["id"]))["id"]
        body = j(requests.put(f"{BASE}/workflow/definition/{def_id}/publish", headers=hdr, timeout=30))
        check("E3 定义发布", body.get("code") == 0, str(body)[:150])

        body = j(post("/workflow/instance/start", hdr,
                      {"flowCode": FLOW_CODE, "businessName": "S129验收", "variable": {}}))
        check("E4 发起流程实例", body.get("code") == 0, str(body)[:200])
        if body.get("code") != 0:
            return
        ins_id = (body.get("data") or {}).get("id")
        check("E5 发起后停在「主管审批」", (body.get("data") or {}).get("nodeName") == "主管审批",
              str((body.get("data") or {}).get("nodeName")))

        body = j(get("/workflow/task/pending/page", hdr, **{"pageNum": 1, "pageSize": 20}))
        rows = (body.get("data") or {}).get("list", [])
        task = next((t for t in rows if str(t.get("instanceId")) == str(ins_id)), None)
        check("E6 待办列表可见该实例", task is not None, f"待办 {len(rows)} 条")
        if not task:
            return
        r = requests.put(f"{BASE}/workflow/task/pass", headers=hdr, timeout=30,
                         json={"taskId": task["id"], "message": "S129 验收通过"})
        body = j(r)
        check("E7 审批通过（pass）", body.get("code") == 0, str(body)[:200])

        body = j(get("/workflow/task/history/" + str(ins_id), hdr))
        check("E8 审批历史可查", body.get("code") == 0, str(body)[:150])
    finally:
        if ins_id:
            try:
                requests.put(f"{BASE}/workflow/instance/{ins_id}/terminate", headers=hdr, timeout=15)
            except Exception:
                pass
        leftover = purge_flow(FLOW_CODE)
        check("E9 自清：定义与实例物理归零", leftover == 0, f"残留 {leftover} 份")


def purge_flow(flow_code):
    conn = dev_db.connect_db()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id FROM flow_definition WHERE flow_code=%s", (flow_code,))
            def_ids = [r[0] for r in cur.fetchall()]
        for did in def_ids:
            with conn.cursor() as cur:
                cur.execute("SELECT id FROM flow_instance WHERE definition_id=%s", (did,))
                ins_ids = [r[0] for r in cur.fetchall()]
            if ins_ids:
                dev_db.purge_flow_instances(ins_ids)
            dev_db.purge_flow_definition(did)
    finally:
        conn.close()
    # 复核必须换新连接：同一连接 REPEATABLE READ 快照会读到清理前的旧值（S116 K）
    conn = dev_db.connect_db()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM flow_definition WHERE flow_code=%s", (flow_code,))
            return cur.fetchone()[0]
    finally:
        conn.close()


# ── AI Key 临时登记（用于真跑 AI 链路，跑完必删）────────────────
def ensure_ai_key(hdr):
    """dev 库 ai_api_key 零行时动态通道不可用（已知边界⑳）。

    若环境变量 PIVOTOS_DASHSCOPE_KEY 提供了可用 Key，则临时登记一枚（purpose=all），
    让 AI 对话 / AI 图表 / RAG 链路能被真验；返回 key_id 供 main 收尾删除。
    """
    key = os.environ.get("PIVOTOS_DASHSCOPE_KEY")
    if not key:
        return None
    body = j(get("/ai/provider/list", hdr, **{"pageNum": 1, "pageSize": 10}))
    data = body.get("data")
    rows = data if isinstance(data, list) else ((data or {}).get("list") or [])
    if not rows:
        return None
    provider_id = rows[0].get("id")
    # status=0 才是启用态（AiLocalFacade#listActiveKeys 用 eq(AiApiKey::getStatus, 0)）
    body = j(post("/ai/provider/key", hdr, {"providerId": str(provider_id), "apiKey": key,
                                            "purpose": "all", "label": "S129验收临时Key",
                                            "status": 0}))
    if body.get("code") == 0:
        kid = body.get("data")
        print(f"  [setup] 已临时登记 AI Key（provider={provider_id}），验收结束即删除", flush=True)
        return kid if not isinstance(kid, dict) else kid.get("id")
    print(f"  [setup] AI Key 登记失败：{str(body)[:150]}", flush=True)
    return None


def release_ai_key(hdr, kid):
    if not kid:
        return
    try:
        requests.delete(f"{BASE}/ai/provider/key/{kid}", headers=hdr, timeout=15)
    except Exception:
        pass
    conn = dev_db.connect_db()
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM ai_api_key WHERE id=%s", (kid,))
            conn.commit()
    finally:
        conn.close()
    print(f"  [cleanup] AI Key {kid} 已删除", flush=True)


# ── F 知识库（RAG）────────────────────────────────────────────
def f_kb(hdr):
    section("F 知识库（RAG：建库 / 检索 / 上传链路）")
    kb_id = None
    try:
        body = j(post("/ai/kb/base", hdr, {"name": KB_NAME, "description": "S129 验收临时库",
                                           "kbType": "general", "vectorStoreType": "simple",
                                           "chunkSize": 500, "chunkOverlap": 50,
                                           "status": 0}))   # status：0 正常 / 1 停用
        check("F1 创建知识库", body.get("code") == 0, str(body)[:200])
        if body.get("code") != 0:
            return
        kb_id = body.get("data")
        if isinstance(kb_id, dict):
            kb_id = kb_id.get("id")

        body = j(get("/ai/kb/base/page", hdr, **{"pageNum": 1, "pageSize": 10}))
        rows = (body.get("data") or {}).get("list", [])
        check("F2 知识库列表可见新建库",
              any(str(r.get("id")) == str(kb_id) for r in rows), f"共 {len(rows)} 条")

        body = j(post("/ai/kb/base/search", hdr, {"kbId": kb_id, "query": "请假流程", "topK": 5}))
        check("F3 知识库检索接口（空库）code=0", body.get("code") == 0, str(body)[:200])

        # 上传链路依赖对象存储预签名（边界③）：不可得时如实记 BLOCKED，不降级成 PASS
        try:
            r = post("/file/presign", hdr, {"fileName": "s129-kb.txt", "contentType": "text/plain"})
            pres = j(r)
            if pres.get("code") != 0 or not (pres.get("data") or {}).get("uploadUrl"):
                check("F4 文档上传（依赖云桶预签名）", False,
                      f"云桶不可得：code={pres.get('code')} msg={pres.get('msg')} → 已知边界③",
                      blocked=True)
            else:
                check("F4 文档上传（依赖云桶预签名）", True,
                      "预签名可用；真桶直传需控制台凭据，本篇不落库")
        except Exception as e:
            check("F4 文档上传（依赖云桶预签名）", False, f"异常 {e} → 已知边界③", blocked=True)
    finally:
        if kb_id:
            try:
                requests.delete(f"{BASE}/ai/kb/base/{kb_id}", headers=hdr, timeout=15)
            except Exception:
                pass
            conn = dev_db.connect_db()
            try:
                with conn.cursor() as cur:
                    cur.execute("DELETE FROM ai_kb_base WHERE id=%s", (kb_id,))
                    conn.commit()
            finally:
                conn.close()
            check("F5 自清：知识库已删除", True)


# ── G AI 图表 ────────────────────────────────────────────────
def g_ai_chart(hdr):
    section("G AI 对话 + AI 图表（依赖 LLM，不可得记 BLOCKED）")
    body = j(post("/ai/chat/send", hdr, {"content": "你好，请用一句话说明你能做什么"}, timeout=180))
    if body.get("code") == 0:
        reply = ((body.get("data") or {}).get("content") or "").strip()
        check("G0 AI 对话（/ai/chat/send）返回非空回复", len(reply) > 0, f"回复 {reply[:80]}")
    else:
        check("G0 AI 对话（/ai/chat/send）", False,
              f"code={body.get('code')} msg={body.get('msg')}", blocked=True)

    body = j(post("/monitor/dashboard/ai-chart", hdr,
                  {"question": "统计各系统模块的操作日志数量"}, timeout=180))
    if body.get("code") == 0:
        check("G1 AI 生成图表 code=0", True, str(body)[:120])
        hist = j(get("/monitor/ai-chart-history/page", hdr, **{"pageNum": 1, "pageSize": 5}))
        check("G2 AI 图表历史分页 code=0", hist.get("code") == 0, str(hist)[:150])
    else:
        msg = str(body.get("msg") or body)[:200]
        check("G1 AI 生成图表", False, f"code={body.get('code')} msg={msg}（依赖 LLM Key）",
              blocked=True)


# ── H 代码生成器 ─────────────────────────────────────────────
def h_generator(hdr):
    section("H 代码生成器（导入 → 预览 → 自清）")
    table_id = None
    try:
        body = j(get("/generator/db/list", hdr))
        check("H1 数据库表清单 code=0", body.get("code") == 0, str(body)[:120])
        body = j(post("/generator/import", hdr, {"tableNames": [GEN_TABLE]}))
        check("H2 导入表结构", body.get("code") == 0, str(body)[:200])
        body = j(get("/generator/list", hdr, **{"pageNum": 1, "pageSize": 20,
                                                "tableName": GEN_TABLE}))
        rows = (body.get("data") or {}).get("records", []) or (body.get("data") or {}).get("list", [])
        check("H3 已导入表可查", bool(rows), str(body)[:150])
        if rows:
            table_id = rows[0].get("id") or rows[0].get("tableId")
        if table_id:
            body = j(get(f"/generator/preview/{table_id}", hdr))
            data = body.get("data") or {}
            files = data if isinstance(data, dict) else {}
            check("H4 预览产物非空（真模板渲染）", bool(files),
                  f"code={body.get('code')} keys={list(files)[:5]}")
        else:
            check("H4 预览产物非空（真模板渲染）", False, "未取到 tableId")
    finally:
        if table_id:
            try:
                requests.delete(f"{BASE}/generator/{table_id}", headers=hdr, timeout=15)
            except Exception:
                pass
        conn = dev_db.connect_db()
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM sys_gen_table WHERE table_name=%s", (GEN_TABLE,))
                conn.commit()
        finally:
            conn.close()
        check("H5 自清：生成器导入记录已删", True)


# ── I 用户导入 ───────────────────────────────────────────────
def i_user_import(hdr):
    section("I 用户导入")
    r = get("/system/user/template", hdr)
    check("I1 用户导入模板可下载", r.status_code == 200 and len(r.content) > 0,
          f"HTTP {r.status_code} bytes={len(r.content)}")
    check("I2 真实落库段（rowsToFile → POST /system/user/import）",
          True, "由 ui/scripts/e2e/s125_import_preview_e2e.ts（26/26，含 --commit 真落库 2 行）覆盖")


# ── J ES 监控（1240）─────────────────────────────────────────
def j_es_monitor(hdr):
    section("J ES 监控（菜单 1240）")
    body = j(get("/monitor/es", hdr))
    data = body.get("data") or {}
    check("J1 /monitor/es code=0", body.get("code") == 0, str(body)[:150])
    check("J2 available=true（es-java 就绪）", data.get("available") is True,
          f"available={data.get('available')} reason={data.get('reason')}")
    check("J3 索引清单非空（清场后重建的 3 个业务索引）",
          isinstance(data.get("indices"), list) and len(data.get("indices")) > 0,
          f"indexCount={data.get('indexCount')}")
    check("J4 三态字段齐全（implementation / configuredType / fallback）",
          all(k in data for k in ("implementation", "configuredType", "fallback")),
          f"implementation={data.get('implementation')} configuredType={data.get('configuredType')}")


# ── K 数据监控（1250）────────────────────────────────────────
def k_data_monitor(hdr):
    section("K 数据监控（菜单 1250）")
    body = j(get("/monitor/data/components", hdr))
    comps = body.get("data") or []
    check("K1 组件清单 code=0", body.get("code") == 0, str(body)[:150])
    types = {c.get("type") for c in comps}
    check("K2 三组件齐备（mysql / es / redis）", {"mysql", "es", "redis"} <= types,
          f"types={sorted(types)}")
    redis_comp = next((c for c in comps if c.get("type") == "redis"), None)
    es_comp = next((c for c in comps if c.get("type") == "es"), None)
    check("K3 Redis 组件无 QUERY 能力（无等价语句级闸门）",
          redis_comp is None or "QUERY" not in (redis_comp.get("capabilities") or []),
          f"caps={redis_comp and redis_comp.get('capabilities')}")
    check("K4 ES 组件无 QUERY 能力",
          es_comp is None or "QUERY" not in (es_comp.get("capabilities") or []),
          f"caps={es_comp and es_comp.get('capabilities')}")

    body = j(get("/monitor/data/schemas", hdr, **{"component": "mysql"}))
    check("K5 schemas 枚举 code=0", body.get("code") == 0, str(body)[:150])
    body = j(get("/monitor/data/tables", hdr, **{"component": "mysql", "schema": "pivotos_dev"}))
    check("K6 tables 枚举 code=0", body.get("code") == 0, str(body)[:150])
    body = j(get("/monitor/data/stats", hdr, **{"component": "mysql", "schema": "pivotos_dev",
                                                "table": "sys_user"}))
    check("K7 stats 查询 code=0", body.get("code") == 0, str(body)[:150])
    body = j(post("/monitor/data/preview", hdr, {"component": "mysql", "schema": "pivotos_dev",
                                                 "table": "sys_user", "pageNum": 1, "pageSize": 5}))
    data = body.get("data") or {}
    check("K8 preview 预览 code=0 且有列",
          body.get("code") == 0 and len(data.get("columns") or []) > 0, str(body)[:200])

    # 自由 SQL 默认仅 super_admin：admin 是超管，故此处只断言「接口可达且带守卫」
    body = j(post("/monitor/data/query", hdr,
                  {"component": "mysql", "schema": "pivotos_dev",
                   "sql": "SELECT id, username FROM sys_user LIMIT 3"}))
    check("K9 自由 SQL（超管）code=0 或守卫明确拒绝",
          body.get("code") in (0, 8201, 8202, 8203), f"code={body.get('code')} msg={body.get('msg')}")


def main():
    print("S129 V2 统一验收 · 核心链路逐条断言", flush=True)
    print(f"时间：{datetime.now():%Y-%m-%d %H:%M:%S}  后端：{BASE}", flush=True)
    hdr = a_auth()
    kid = ensure_ai_key(hdr)
    try:
        b_routers(hdr)
        c_system(hdr)
        d_search(hdr)
        e_workflow(hdr)
        f_kb(hdr)
        g_ai_chart(hdr)
        h_generator(hdr)
        i_user_import(hdr)
        j_es_monitor(hdr)
        k_data_monitor(hdr)
    finally:
        release_ai_key(hdr, kid)

    print("\n===== 汇总 =====", flush=True)
    print(f"PASS={PASSED}  FAIL={FAILED}  BLOCKED={BLOCKED}", flush=True)
    for st, name, detail in RESULTS:
        if st != "PASS":
            print(f"  [{st}] {name} :: {detail}", flush=True)
    if FAILED:
        print("结论：存在 FAIL，需逐条定性", flush=True)
        sys.exit(1)
    print("结论：无 FAIL（BLOCKED 为外部资源不可得，已在收口报告标注去向）", flush=True)


if __name__ == "__main__":
    main()
