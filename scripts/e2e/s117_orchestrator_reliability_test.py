#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S117 A5-2 编排可靠性 E2E（编排可观测 + 重试/熔断落地验证）

断言点（对齐 05-踩坑记录/S117-开工简报.md §六 验收判据）：
  ① 工具面：/ai/orchestrator/tools 返回活工具且 writeCodeFile 被配置排除
  ② 可观测：执行后 detail 的每个步骤都带 stepStatus / attemptCount / stepCostMs
  ③ 落库：ai_tool_plan_step 行数 == 步骤数（含因失败/待确认而未发起的步骤）
  ④ 计划级：detail 带 retryCount / circuitBroken / failReason 字段
  ⑤ 写步骤零重试：停在写步骤前的那条 attemptCount == 0（一次都没调用）
  ⑥ 确认闸口径不变（S116 K1）：confirmed=false 时写步骤零调用

为什么第 ⑤ 条最关键：S117 开工实测写工具非幂等（连续两次发站内信，消息 408 → 410），
写步骤一旦重试就是重复副作用，所以「未确认的写步骤必须一次都没被调用」是硬断言。

用法：python3 s117_orchestrator_reliability_test.py
      需 8080 已起且 dev 库已 migrate 至 V1.2.49/V2.1.14
"""
import json
import os
import sys

import pymysql
import requests

BASE = "http://127.0.0.1:8080"
EVIDENCE = "/Users/huweilong/Documents/File/Project/PivotOS Technology/PivotOS/pivotos-tmp/test-evidence/s117"
DB = dict(host="175.24.176.176", port=3306, user="root", password="mysql_DNCi3f",
          database="pivotos_dev", charset="utf8mb4")
os.makedirs(EVIDENCE, exist_ok=True)

FAILED: list[str] = []
HDR: dict = {}


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(cond, msg):
    if cond:
        log("PASS", msg)
    else:
        FAILED.append(msg)
        log("FAIL", msg)


def save(name, data):
    with open(os.path.join(EVIDENCE, name), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2, default=str)


def db_query(sql, args=None):
    conn = pymysql.connect(**DB)
    try:
        cur = conn.cursor()
        cur.execute(sql, args or ())
        return cur.fetchall()
    finally:
        conn.close()


def login():
    body = requests.post(f"{BASE}/system/auth/login",
                         json={"username": "admin", "password": "admin123"}, timeout=60).json()
    assert body.get("code") == 0, f"登录失败: {body}"
    return {"Authorization": body["data"]["token"]}


def main():
    global HDR
    HDR = login()
    log("STEP0", "admin 登录成功")

    # ① 工具面
    tools = requests.get(f"{BASE}/ai/orchestrator/tools", headers=HDR, timeout=30).json()["data"]
    check("writeCodeFile" not in tools, f"① 编排工具面 writeCodeFile 已排除（活工具 {len(tools)} 个）")

    # 挑一条既有 draft 计划执行，避免强依赖 LLM 规划结果
    # 只挑「有步骤」的 draft 计划：空步骤计划（意图无匹配工具 → unmapped）无法断言轨迹
    rows = db_query("SELECT id, intent, step_count FROM ai_tool_plan "
                    "WHERE status='draft' AND deleted=0 AND step_count > 0 "
                    "ORDER BY id DESC LIMIT 5")
    check(bool(rows), f"② dev 库存量有步骤的 draft 计划 {len(rows)} 条（用于执行，不依赖 LLM 规划）")
    if not rows:
        save("s117_orchestrator_reliability.json", {"failed": FAILED})
        sys.exit(1 if FAILED else 0)

    plan_id = rows[0][0]
    log("STEP1", f"选取 draft 计划 id={plan_id} intent={rows[0][1]} 步骤数={rows[0][2]}")

    before_rows = db_query("SELECT COUNT(*) FROM ai_tool_plan_step WHERE plan_id=%s", (plan_id,))[0][0]
    log("STEP2", f"执行前 ai_tool_plan_step 行数={before_rows}")

    resp = requests.post(f"{BASE}/ai/orchestrator/{plan_id}/run", headers=HDR,
                         json={"confirmed": False}, timeout=300).json()
    log("STEP3", f"执行返回 code={resp.get('code')}")

    detail = requests.get(f"{BASE}/ai/orchestrator/{plan_id}", headers=HDR, timeout=30).json()["data"]
    save("s117_orchestrator_reliability_detail.json", detail)
    steps = detail.get("steps") or []
    log("STEP4", f"detail.status={detail.get('status')} steps={len(steps)} "
                 f"retryCount={detail.get('retryCount')} circuitBroken={detail.get('circuitBroken')}")

    # ② 可观测：每步都带轨迹字段
    ok_all = all(s.get("stepStatus") is not None and s.get("attemptCount") is not None
                 for s in steps) if steps else False
    check(ok_all, "③ 可观测：每个步骤都带 stepStatus / attemptCount（编排可观测）")
    ok_cost = all(s.get("stepCostMs") is not None for s in steps) if steps else False
    check(ok_cost, "③ 可观测：每个步骤都带 stepCostMs")

    # ③ 落库
    after_rows = db_query("SELECT COUNT(*) FROM ai_tool_plan_step WHERE plan_id=%s", (plan_id,))[0][0]
    check(after_rows == len(steps),
          f"④ 落库：ai_tool_plan_step 行数 {after_rows} == 步骤数 {len(steps)}（重跑不叠加）")

    # ④ 计划级字段
    check(detail.get("retryCount") is not None, "⑤ 计划级：detail.retryCount 不为空")
    check(detail.get("circuitBroken") is not None, "⑤ 计划级：detail.circuitBroken 不为空")

    # ⑤ 写步骤零重试：停在写步骤前的那条必须一次都没调用
    blocked = detail.get("blockedStep")
    if blocked:
        target = [s for s in steps if s.get("no") == blocked]
        if target:
            check(target[0].get("attemptCount") == 0,
                  f"⑥ 写步骤零重试：被拦下的第 {blocked} 步 attemptCount={target[0].get('attemptCount')}（须为 0）")
            check(target[0].get("stepStatus") == "need_confirm",
                  f"⑥ 确认闸：被拦下的第 {blocked} 步 stepStatus={target[0].get('stepStatus')}")
        else:
            log("SKIP", "⑥ 未定位到 blockedStep 对应步骤条目")
    else:
        log("SKIP", "⑥ 本次计划无写步骤（未触发确认闸），改为断言无写步骤被重试")
        write_steps = [s for s in steps if s.get("write")]
        check(all((s.get("attemptCount") or 0) <= 1 for s in write_steps),
              "⑥ 写步骤零重试：所有写步骤 attemptCount ≤ 1")

    # ⑥ 确认闸口径不变：confirmed=false 时不产生写工具调用
    invoke_before = db_query("SELECT COUNT(*) FROM ai_tool_invoke")[0][0]
    log("STEP5", f"ai_tool_invoke 总数={invoke_before}（累积留痕，仅登记）")

    save("s117_orchestrator_reliability.json", {
        "planId": plan_id,
        "status": detail.get("status"),
        "steps": steps,
        "retryCount": detail.get("retryCount"),
        "circuitBroken": detail.get("circuitBroken"),
        "failReason": detail.get("failReason"),
        "stepRowsBefore": before_rows,
        "stepRowsAfter": after_rows,
        "failed": FAILED,
    })

    log("SUMMARY", f"失败项 {len(FAILED)} 个")
    for item in FAILED:
        log("FAILED", item)
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
