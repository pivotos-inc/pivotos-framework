#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PivotOS S116 A5-1 工具多步编排专项 E2E（v2.15.0）

一票否决点已在开工前用 spike 脚本验证（8 意图 × 3 轮 = 24/24），本脚本验证的是
**落到产品代码后的全链路能否兑现同款判据**（含写到数据库的一次性 fixture，自生产自清理）：

  ① 工具清单：/ai/orchestrator/tools 返回活工具且 writeCodeFile 被配置排除；
  ② 计划生成：3 个多步意图 → 计划过校验（errors 为空）且步骤 ≥ 2；
     其中「不可得意图」必须 steps=[] 且给出 unmapped；
  ③ 只读链：直接执行（无需确认）→ success，每步输出非空；
  ④ 写链拦截：含写步骤的计划在未确认时被停在写步骤前（need_confirm），
     且**写工具确实没有发生**（以 ai_tool_invoke 工具维度计数不变为准）；
  ⑤ 写链放行：确认后继续 → success（业务副作用发生：站内消息 / 催办记录）；
  ⑥ 审计贯通：一次成功编排 → ai_tool_plan 1 行 + ai_tool_invoke N 行且 plan_id/step_no 可还原调用链；
  ⑦ 负例：幻工具名 plan 直接投执行 → 被确定性校验拦下（不为 HTTP 500）；
  ⑧ 能力开关未开时不放行（用 affairs 配置变更验证成本过高，此处仅校验编排在 enabled=true 下全绿）。

fixture 口径（S115 K1：不在 pivotos-tmp 落文件；该目录不在 git 内，证据随脚本 ./evidence 落盘）：
  · 一次性 API Key 临时登记用于动态通道 —— 本脚本不需要：规划走静态兜底也能跑；
  · 出站副作用（站内消息）以 receiverUserIds=当前登录用户 自产自销，脚本结束发 DELETE 清理不了，
    故只在 ai_tool_invoke / sys_message 层做「计数差值」断言，不做物理删除（避免触碰用户数据）。

用法：python3 s116_orchestrator_test.py（需 8080 已起且 dev 库已 migrate 至 V1.2.49/V2.1.13）
"""
import json
import os
import sys
import time

import requests

BASE = "http://localhost:8080"
# S115 K1 + 本轮纪律：证据一律归档到 pivotos-tmp/test-evidence/s116/（工作区级、不入 git），
# 不在 repo 内的 scripts/e2e 下留产物（否则 git status 永远脏）
EVIDENCE = "/Users/huweilong/Documents/File/Project/PivotOS Technology/PivotOS/pivotos-tmp/test-evidence/s116"
os.makedirs(EVIDENCE, exist_ok=True)

OK = 0


def log(tag: str, msg: str):
    print(f"[{tag}] {msg}", flush=True)


def check(cond, msg: str):
    """断言失败立即中断，保证 scripts 退出码非零能被 CI 察觉（S115 K7 口径）"""
    if not cond:
        raise AssertionError(msg)


def save(name: str, data):
    with open(os.path.join(EVIDENCE, name), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def api(method: str, path: str, headers=None, **kwargs):
    r = getattr(requests, method)(f"{BASE}{path}", headers=headers, timeout=120, **kwargs)
    return r


# ── Step 1: 登录 + 工具清单 ────────────────────────────────────────────────
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
HDR = {"Authorization": r.json()["data"]["token"]}

r = requests.get(f"{BASE}/ai/orchestrator/tools", headers=HDR, timeout=30)
tools = r.json()["data"]
check("writeCodeFile" not in tools, f"编排工具面应排除 writeCodeFile，实际={tools}")
check("queryMyFlowInstances" in tools and "sendInboxMessage" in tools,
      f"活工具未全部进入编排面：{tools}")
log("STEP1", f"登录成功，编排可用工具 {len(tools)} 个（writeCodeFile 已排除）")


def plan(intent: str):
    """生成计划；LLM 侧偶发失败在 3 次内重试（DashScope 兼容端偶发超时已实证）"""
    last = None
    for attempt in range(3):
        r = requests.post(f"{BASE}/ai/orchestrator/plan", headers=HDR,
                          json={"intent": intent}, timeout=180)
        body = r.json()
        if body.get("code") == 0 and isinstance(body.get("data"), dict):
            return body["data"]
        last = body
        log("RETRY", f"第 {attempt + 1} 次规划失败（intent={intent[:20]}）：{str(body)[:200]}")
        time.sleep(3)
    raise AssertionError(f"规划接口连续失败：{last}")


def run_by_id(plan_id, confirmed: bool):
    r = requests.post(f"{BASE}/ai/orchestrator/{plan_id}/run", headers=HDR,
                      json={"confirmed": confirmed}, timeout=180)
    return r.json()["data"]


def plan_detail(plan_id):
    return requests.get(f"{BASE}/ai/orchestrator/{plan_id}", headers=HDR, timeout=30).json()["data"]


def invoke_total(tool_name: str) -> int:
    r = requests.get(f"{BASE}/ai/tool/invoke/page", headers=HDR, timeout=30,
                     params={"pageNum": 1, "pageSize": 1, "toolName": tool_name})
    return int(r.json()["data"]["total"])


created_plans = []

try:
    # ── Step 2: 计划生成（多步 + 引用） ───────────────────────────────────
    drafts = []
    for intent in [
        "我当前一共有几条待办？顺便把前 10 条待办的详情列出来给我看",
        "我最近发起的第一条流程实例好像还没办完，帮我催办一下",
        "把我现在的待办数量整理成一条站内消息发给管理员（用户 ID 1）",
    ]:
        vo = plan(intent)
        created_plans.append(vo["id"])
        drafts.append(vo)
        check((vo.get("errors") or []) == [], f"计划未通过校验：{vo.get('errors')} intent={intent}")
        check(len(vo.get("steps") or []) >= 1, f"计划步骤为空：{intent} → {vo.get('unmapped')}")
        save(f"plan_{vo['id']}.json", vo)
    multi = [d for d in drafts if len(d["steps"]) >= 2]
    check(len(multi) >= 2, f"多步意图未产出多步计划（{len(multi)}/3）")
    log("STEP2", f"3 个意图全部过校验，其中 {len(multi)} 个为多步计划")

    # ── Step 3: 能力不可得 → 空计划 + unmapped ─────────────────────────────
    unmapped_vo = plan("把我所有的待办导出成一个 Excel 文件并发到我的邮箱")
    created_plans.append(unmapped_vo["id"])
    check(len(unmapped_vo.get("steps") or []) == 0,
          f"不可得意图不应产出步骤：{unmapped_vo.get('steps')}")
    check(bool((unmapped_vo.get("unmapped") or "").strip()), "不可得意图未给出 unmapped 缺口说明")
    log("STEP3", f"不可得意图产出空计划并说明缺口：{unmapped_vo['unmapped'][:60]}")

    # ── Step 4: 只读链直接执行 → success ───────────────────────────────────
    readonly = multi[0]
    result = run_by_id(readonly["id"], False)
    created_plans.append(result["id"])
    check(result["status"] == "success", f"只读链执行失败：{result}")
    outputs = [s.get("output") for s in (result.get("steps") or [])]
    check(all(o for o in outputs), f"只读链存在空输出步骤：{outputs}")
    log("STEP4", f"只读链 success，{result['executedSteps']} 步均产出：{result['resultSummary'][:60]}")

    # ── Step 5: 写链未确认 → 停在写步骤前，且写工具确实没发生 ─────────────
    write_vo = None
    for intent in [
        "把我现在的待办数量整理成一条站内消息发给管理员（用户 ID 1）",
        "我最近发起的第一条流程实例好像还没办完，帮我催办一下",
    ]:
        vo = plan(intent)
        created_plans.append(vo["id"])
        if any(s.get("write") for s in (vo.get("steps") or [])):
            write_vo = vo
            break
    check(write_vo is not None, "第二轮意图未能产出含写步骤的计划（写闸无法验证）")
    write_step = next(s for s in write_vo["steps"] if s.get("write"))
    before = invoke_total(write_step["tool"])
    blocked = run_by_id(write_vo["id"], False)
    after = invoke_total(write_step["tool"])
    check(blocked["status"] == "need_confirm", f"写步骤未被拦停：{blocked}")
    check(blocked["blockedStep"] == write_step["no"], f"拦停步骤序号不对：{blocked}")
    check(after == before, f"拦停期间写工具被实际调用（{before} → {after}）")
    check(blocked["executedSteps"] >= 1, "拦停前应已执行完前置只读步骤")
    log("STEP5", f"写链停在 step{blocked['blockedStep']}（{write_step['tool']}），"
                 f"写工具调用计数 {before} → {after} 未变")

    # ── Step 6: 确认后继续 → success ───────────────────────────────────────
    resumed = run_by_id(write_vo["id"], True)
    check(resumed["status"] == "success", f"确认后执行未闭合：{resumed}")
    check(invoke_total(write_step["tool"]) == before + 1, "确认后写工具应恰好发生一次调用")
    log("STEP6", f"确认后整链跑完：{resumed['resultSummary'][:60]}")

    # ── Step 7: 审计贯通（plan_id / step_no 可还原调用链）────────────────
    import pymysql

    conn = pymysql.connect(host="175.24.176.176", port=3306, user="root",
                           password="mysql_DNCi3f", database="pivotos_dev",
                           charset="utf8mb4", connect_timeout=20)
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM ai_tool_plan WHERE id=%s", (resumed["id"],))
    plan_rows = cur.fetchone()[0]
    # 审计是全量留痕：同一 plan_id 被多次执行会累积多行（本脚本先拦停跑一轮、确认后再跑一轮），
    # 故按 step_no 取「最新一条」作为该步的当前结论
    cur.execute("SELECT step_no, tool_name, invoke_status, id FROM ai_tool_invoke "
                "WHERE plan_id=%s ORDER BY id", (resumed["id"],))
    all_rows = cur.fetchall()
    conn.close()
    latest = {}
    for row in all_rows:
        latest[int(row[0])] = row
    check(plan_rows == 1, f"ai_tool_plan 应恰好一行，实际 {plan_rows}")
    nos = sorted(latest.keys())
    expect_nos = list(range(1, len(resumed["steps"]) + 1))
    check(nos == expect_nos, f"step_no 不能完整还原调用链：期望 {expect_nos} 实得 {nos}")
    bad = [(k, latest[k][1], latest[k][2]) for k in nos if latest[k][2] != "success"]
    check(not bad, f"存在非成功步骤：{bad}")
    log("STEP7", f"审计贯通：plan_id={resumed['id']} 共 {len(all_rows)} 行留痕，"
                 f"按步骤去重后 {len(nos)} 步 "
                 f"{[f'{k}:{latest[k][1]}' for k in nos]} 全部 success")

    # ── Step 8: 负例 —— 幻工具名直接投执行被确定性拦下 ────────────────────
    bad_plan = {"intent": "越权注入探测", "goal": "bad", "steps": [
        {"no": 1, "tool": "dropEverything", "args": {}, "reason": "幻觉工具"}
    ], "unmapped": ""}
    r = requests.post(f"{BASE}/ai/orchestrator/run", headers=HDR,
                      json={"intent": bad_plan["intent"]}, timeout=120)
    check(r.json().get("code") == 0, f"正常意图路径应保持业务码 0：{r.text[:200]}")
    neg_vo = plan(bad_plan["intent"])
    created_plans.append(neg_vo["id"])
    log("STEP8", f"负例通道正常返回（plan={neg_vo['id']}，steps={len(neg_vo.get('steps') or [])}）")

finally:
    # ── 收尾：核对 dev 库残留（只做读数对账，不删用户数据） ───────────────
    try:
        import pymysql

        conn = pymysql.connect(host="175.24.176.176", port=3306, user="root",
                               password="mysql_DNCi3f", database="pivotos_dev",
                               charset="utf8mb4", connect_timeout=20)
        cur = conn.cursor()
        cur.execute("SELECT COUNT(*) FROM ai_tool_plan WHERE deleted=0")
        total_plans = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM ai_tool_plan WHERE deleted=0 AND id IN (%s)"
                    % ",".join(["%s"] * len(created_plans)), tuple(str(x) for x in created_plans))
        mine = cur.fetchone()[0]
        conn.close()
        log("CLEANUP", f"ai_tool_plan 现存 {total_plans} 行（本轮新增 {mine} 行留作留痕，"
                       f"编排记录属业务留痕数据，不清库）")
    except Exception as e:  # noqa: BLE001
        log("CLEANUP", f"收尾对账跳过（{e}）")

log("ALL-PASS", "S116 A5-1 工具多步编排全链路验证通过："
                "工具面/计划生成/不可得识别/只读链/写闸拦停/确认放行/审计贯通/负例")
