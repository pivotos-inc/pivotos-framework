#!/usr/bin/env python3
"""
阶段7 迁移步骤执行 + 评审门禁 E2E 测试
前置：自测任务已处于 PLANNED(6)，含 15 个步骤
流程：执行步骤1 → 自测通过 → 评审PASS → 已完成
     执行步骤2 → 评审REJECT → 已驳回 → 重新执行 → 自测通过
"""
import requests, json, time, sys

BASE = "http://localhost:8080"
TASK_ID = "2089577593306185730"

STEP_STATUS = {0:'待执行',1:'执行中',2:'自测通过',3:'评审中',4:'已通过',
               5:'已驳回',6:'已完成',7:'失败',8:'回滚中',9:'已回滚'}

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

def check(r, step):
    if r.status_code != 200:
        log("FAIL", f"{step} HTTP {r.status_code}: {r.text[:300]}")
        sys.exit(1)
    body = r.json()
    if not body.get("success") or body.get("code") != 0:
        log("FAIL", f"{step} 业务失败: {json.dumps(body, ensure_ascii=False)[:300]}")
        sys.exit(1)
    return body.get("data")

# ── Step 1: 登录 ──────────────────────────────────────────
log("STEP1", "登录 ...")
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=10)
token = check(r, "登录")["token"]
HDR = {"Authorization": token}
log("STEP1", "OK")

# ── Step 2: 确认任务 PLANNED + 获取步骤列表 ───────────────
r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
task = check(r, "查询任务")
log("STEP2", f"任务状态={task['status']}  totalSteps={task.get('totalSteps')}")
if task["status"] not in (6, 7):
    log("FAIL", f"任务状态不是 PLANNED(6)/EXECUTING(7)，当前={task['status']}")
    sys.exit(1)

r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
steps = check(r, "步骤列表")
if len(steps) < 2:
    log("FAIL", f"步骤数不足：{len(steps)}")
    sys.exit(1)
step1, step2 = steps[0], steps[1]
log("STEP2", f"步骤1: {step1['name']}  步骤2: {step2['name']}  OK")

# ── Step 3: 执行步骤1（幂等：已自测通过则跳过）────────────
r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
s1 = next(s for s in check(r, "步骤列表") if s["id"] == step1["id"])
if s1["status"] in (2, 6):
    log("STEP3", f"步骤1 已处于自测通过/已完成，跳过执行")
else:
    log("STEP3", f"执行步骤1 (id={step1['id']}) ...")
    r = requests.post(f"{BASE}/migration/step/execute",
                      params={"stepId": step1["id"]}, headers=HDR, timeout=300)
    data = check(r, "执行步骤1")
    log("STEP3", f"执行摘要: {str(data)[:200]}  OK")

    # 验证步骤状态 = SELF_TEST_PASSED(2)
    r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
    steps = check(r, "步骤列表")
    s1 = next(s for s in steps if s["id"] == step1["id"])
    log("STEP3", f"步骤1 状态={STEP_STATUS.get(s1['status'])}")
    if s1["status"] != 2:
        log("FAIL", f"步骤1 未进入自测通过，当前状态={s1['status']} errorMsg={s1.get('errorMsg')}")
        sys.exit(1)

# ── Step 4: 验证产物 ──────────────────────────────────────
log("STEP4", "验证步骤1产物 ...")
r = requests.get(f"{BASE}/migration/artifact/list",
                 params={"stepId": step1["id"]}, headers=HDR, timeout=10)
artifacts = check(r, "产物列表")
if len(artifacts) == 0:
    log("FAIL", "步骤1 产物数为 0")
    sys.exit(1)
log("STEP4", f"产物数={len(artifacts)}  OK")
for a in artifacts[:3]:
    log("STEP4", f"  [{a['artifactType']}] {a['relativePath']}")

# 验证产物详情接口（含 content）
r = requests.get(f"{BASE}/migration/artifact/{artifacts[0]['id']}", headers=HDR, timeout=10)
detail = check(r, "产物详情")
content = detail.get("generatedContent") or ""
if len(content) < 10:
    log("FAIL", f"产物内容为空或过短: {len(content)}")
    sys.exit(1)
log("STEP4", f"产物详情内容长度={len(content)}  OK")

# ── Step 5: 评审 PASS（幂等：已完成则跳过）──────────────
r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
s1 = next(s for s in check(r, "步骤列表") if s["id"] == step1["id"])
if s1["status"] == 6:
    log("STEP5", "步骤1 已完成，跳过评审")
else:
    log("STEP5", "评审通过步骤1 ...")
    r = requests.post(f"{BASE}/migration/step/review",
                      params={"stepId": step1["id"], "action": "PASS", "comment": "E2E 自动评审通过"},
                      headers=HDR, timeout=30)
    check(r, "评审PASS")
r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
task = check(r, "查询任务")
log("STEP5", f"任务状态={task['status']}  completedSteps={task.get('completedSteps')}")
if task.get("completedSteps") != 1:
    log("FAIL", f"completedSteps 应为 1，实际={task.get('completedSteps')}")
    sys.exit(1)
r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
s1 = next(s for s in check(r, "步骤列表") if s["id"] == step1["id"])
if s1["status"] != 6:
    log("FAIL", f"步骤1 应为已完成(6)，实际={s1['status']}")
    sys.exit(1)
log("STEP5", "步骤1 已完成(6)，completedSteps=1  OK")

# ── Step 6: 执行步骤2 并驳回 ──────────────────────────────
log("STEP6", f"执行步骤2 (id={step2['id']}) ...")
r = requests.post(f"{BASE}/migration/step/execute",
                  params={"stepId": step2["id"]}, headers=HDR, timeout=300)
check(r, "执行步骤2")
r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
s2 = next(s for s in check(r, "步骤列表") if s["id"] == step2["id"])
if s2["status"] != 2:
    log("FAIL", f"步骤2 未进入自测通过，当前={STEP_STATUS.get(s2['status'])}")
    sys.exit(1)
log("STEP6", "步骤2 自测通过  OK")

log("STEP6", "评审驳回步骤2 ...")
r = requests.post(f"{BASE}/migration/step/review",
                  params={"stepId": step2["id"], "action": "REJECT", "comment": "E2E 驳回测试"},
                  headers=HDR, timeout=30)
check(r, "评审REJECT")
r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
s2 = next(s for s in check(r, "步骤列表") if s["id"] == step2["id"])
if s2["status"] != 5:
    log("FAIL", f"步骤2 应为已驳回(5)，实际={s2['status']}")
    sys.exit(1)
log("STEP6", "步骤2 已驳回(5)，reviewComment=" + str(s2.get("reviewComment")) + "  OK")

# ── Step 7: 驳回后重新执行 ────────────────────────────────
log("STEP7", "重新执行步骤2（验证 REJECTED 可重执行）...")
r = requests.post(f"{BASE}/migration/step/execute",
                  params={"stepId": step2["id"]}, headers=HDR, timeout=300)
check(r, "重新执行步骤2")
r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
s2 = next(s for s in check(r, "步骤列表") if s["id"] == step2["id"])
if s2["status"] != 2:
    log("FAIL", f"重新执行后步骤2 应为自测通过(2)，实际={s2['status']}")
    sys.exit(1)
log("STEP7", "重新执行成功，步骤2 再次自测通过  OK")

# ── 汇总 ──────────────────────────────────────────────────
r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
task = check(r, "查询任务")
print()
print("=" * 60)
print("  阶段7 步骤执行 + 评审门禁 E2E 测试：全部通过 ✓")
print(f"  任务状态     : {task['status']}")
print(f"  completedSteps: {task.get('completedSteps')} / {task.get('totalSteps')}")
print(f"  步骤1产物数  : {len(artifacts)}")
print("=" * 60)
