#!/usr/bin/env python3
"""
PivotOS S97 对话链路 ToolCallback 挂载 E2E 脚本（2026-08-23 第 5 轮）
目标：同步对话挂载 function calling 后，「AI 查当前用户待办数」Hello World 跑通——
  ① REST 直查待办分页拿基线 total；
  ② POST /ai/chat/send 自然语言提问待办数；
  ③ 断言 AI 回复中包含基线数字（模型经 queryMyPendingTaskCount 工具取数）。
前置：后端已启动（含 S97 工具挂载），dev 库已配置可用 AI 供应商 Key。
"""
import requests, json, sys

BASE = "http://localhost:8080"

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

# ── Step 1: 登录 ──────────────────────────────────────────
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
body = r.json()
assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
token = body["data"]["token"]
HDR = {"Authorization": token}
log("STEP1", f"登录成功 token={token[:16]}...")

# ── Step 2: REST 基线——待办分页 total ─────────────────────
r = requests.get(f"{BASE}/workflow/task/pending/page",
                 params={"pageNum": 1, "pageSize": 10}, headers=HDR, timeout=30)
body = r.json()
assert body.get("code") == 0, f"待办分页失败: {body}"
baseline = body["data"]["total"]
log("STEP2", f"REST 基线待办数 total={baseline}")

# ── Step 3: 对话提问——AI 应经工具取数 ─────────────────────
question = "帮我查一下我当前有几条待办审批任务，直接告诉我数量"
log("STEP3", f"提问：{question}")
r = requests.post(f"{BASE}/ai/chat/send",
                  json={"content": question}, headers=HDR, timeout=120)
body = r.json()
assert body.get("code") == 0, f"对话失败: {json.dumps(body, ensure_ascii=False)[:300]}"
reply = body["data"]["content"] or ""
log("STEP3", f"AI 回复：{reply[:200]}")

# ── Step 4: 断言——回复包含基线数字 ────────────────────────
assert str(baseline) in reply, f"回复未包含基线待办数 {baseline}：{reply[:200]}"
log("PASS4", f"回复包含基线待办数 {baseline}（工具取数链路成立）")
log("ALL-PASS", f"对话链路 ToolCallback Hello World 通过：基线={baseline}，AI 回复命中")
