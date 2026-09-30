#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S117 A4E 审批建议增强 E2E（受控自动预审：默认关闭硬生效 + 归属不越权）

断言点（对齐 05-踩坑记录/S117-开工简报.md §六 验收判据）：
  ① 默认关闭硬生效：开关未显式置 true 时，任何待办都不被自动通过
     ——autoPassed=false 且 reason 含「未启用」
  ② 留痕：ai_approval_advice.auto_passed=0 且 auto_decision_reason 有值
  ③ 归属不越权：非该任务审批人调用 auto-pass 必须被拒（5083）
  ④ 制度类标记为契约字段：ai_kb_base.kb_type 存在（V2.1.16）

为什么不做「开启后真实自动通过」的强断言：LLM 结论三态是概率性的，
dev 库实测大量待办产出 need_info（材料不足），强行断言会通过等于制造偶发红。
因此本脚本对「默认关闭 + 零误放行」做硬断言，自动放行的规则矩阵由单测锁死
（AutoApprovalPolicyTest），真实放行链路另做一次性开启验证并归档证据。

用法：python3 s117_auto_approval_test.py
      需 8080 已起且 dev 库已 migrate 至 V1.2.49/V2.1.15/V2.1.16
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


def login(user, pwd):
    body = requests.post(f"{BASE}/system/auth/login", json={"username": user, "password": pwd},
                         timeout=60).json()
    assert body.get("code") == 0, f"登录失败 {user}: {body}"
    return {"Authorization": body["data"]["token"]}


def main():
    hdr = login("admin", "admin123")
    log("STEP0", "admin 登录成功")

    # ④ 制度类标记列已落地
    cols = [r[0] for r in db_query("SHOW COLUMNS FROM ai_kb_base")]
    check("kb_type" in cols, "④ 制度类标记：ai_kb_base.kb_type 列已落地（V2.1.16）")
    advice_cols = [r[0] for r in db_query("SHOW COLUMNS FROM ai_approval_advice")]
    check("auto_passed" in advice_cols, "④ 留痕列：ai_approval_advice.auto_passed 已落地（V2.1.15）")

    # 取一条 admin 待办
    pend = requests.get(f"{BASE}/workflow/task/pending/page", headers=hdr,
                        params={"pageNum": 1, "pageSize": 5}, timeout=60).json()
    rows = (pend.get("data") or {}).get("list") or []
    check(bool(rows), f"admin 待办 {len(rows)} 条")
    if not rows:
        save("s117_auto_approval.json", {"failed": FAILED})
        sys.exit(1 if FAILED else 0)

    # 挑待办：优先「审批人只有 admin」的单审批人任务。
    # 不能随便拿第一条——dev 库里票签/会签任务有多个审批人，
    # 而 vote002 可能正好是票签人之一，那样越权负例会误判成「被拒失败」。
    task_id = None
    approvers: set[str] = set()
    for row in rows:
        tid = row.get("id") or row.get("taskId")
        ids = {str(r[0]) for r in db_query(
            "SELECT DISTINCT processed_by FROM flow_user WHERE associated=%s", (tid,))}
        if ids == {"1"}:
            task_id, approvers = tid, ids
            break
    if task_id is None:
        task_id = rows[0].get("id") or rows[0].get("taskId")
        approvers = {str(r[0]) for r in db_query(
            "SELECT DISTINCT processed_by FROM flow_user WHERE associated=%s", (task_id,))}
    log("STEP1", f"取待办 taskId={task_id} 审批人集合={sorted(approvers)}")

    # 生成建议（SSE）
    resp = requests.post(f"{BASE}/ai/approval/advice/stream", headers=hdr,
                         json={"taskId": task_id}, timeout=300, stream=True)
    raw = resp.content.decode("utf-8", errors="replace")
    log("STEP2", f"建议流生成完成，长度 {len(raw)}")
    save("s117_auto_approval_stream.txt", raw[:4000])

    # ① 默认关闭硬生效
    r = requests.post(f"{BASE}/ai/approval/auto-pass", headers=hdr,
                      json={"taskId": int(task_id)}, timeout=120).json()
    data = r.get("data") or {}
    log("STEP3", f"auto-pass 返回：autoPassed={data.get('autoPassed')} reason={data.get('reason')}")
    check(r.get("code") == 0, f"① auto-pass 接口调用成功（code={r.get('code')}）")
    check(data.get("autoPassed") is False, "① 默认关闭硬生效：autoPassed 必须为 false")
    check("未启用" in (data.get("reason") or ""),
          f"① 默认关闭硬生效：reason 含「未启用」（实际：{data.get('reason')}）")

    # ② 留痕
    advice_rows = db_query(
        "SELECT id, conclusion, auto_passed, auto_decision_reason FROM ai_approval_advice "
        "WHERE task_id=%s ORDER BY id DESC LIMIT 1", (task_id,))
    if advice_rows:
        row = advice_rows[0]
        check(row[2] == 0, f"② 留痕：ai_approval_advice.auto_passed={row[2]}（须为 0）")
        check(bool(row[3]), f"② 留痕：auto_decision_reason 有值（{str(row[3])[:40]}）")
        log("STEP4", f"建议 id={row[0]} conclusion={row[1]}")
    else:
        FAILED.append("② 留痕：未查到建议记录")
        log("FAIL", "② 留痕：未查到建议记录")

    # ③ 归属不越权：非审批人调用被拒（前提：vote002 不在该任务审批人集合内）
    hdr2 = login("vote002", "Admin@123456")
    vote_rows = db_query("SELECT id FROM sys_user WHERE username='vote002' LIMIT 1")
    vote_uid = str(vote_rows[0][0]) if vote_rows else ""
    log("STEP5", f"vote002 id={vote_uid}，该任务审批人={sorted(approvers)}")
    r2 = {}
    if vote_uid and vote_uid in approvers:
        log("SKIP", "③ 归属负例：vote002 本身是该任务审批人，本任务不构成负例（如实跳过，不伪造结论）")
    else:
        r2 = requests.post(f"{BASE}/ai/approval/auto-pass", headers=hdr2,
                           json={"taskId": int(task_id)}, timeout=120).json()
        log("STEP5", f"非审批人 vote002 调用返回：code={r2.get('code')} msg={r2.get('msg')}")
        check(r2.get("code") != 0, f"③ 归属不越权：非审批人 auto-pass 被拒（code={r2.get('code')}）")

    save("s117_auto_approval.json", {
        "taskId": task_id,
        "approvers": sorted(approvers),
        "autoPass": data,
        "nonApprover": r2,
        "failed": FAILED,
    })

    log("SUMMARY", f"失败项 {len(FAILED)} 个")
    for item in FAILED:
        log("FAILED", item)
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
