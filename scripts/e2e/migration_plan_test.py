#!/usr/bin/env python3
"""
阶段6 迁移计划生成 E2E 测试
任务已处于 ANALYZED(4)，直接测试 POST /plan → 轮询 PLANNED(6) → 验证步骤和计划
"""
import requests, json, time, sys

BASE = "http://localhost:8080"
TASK_ID = "2089577593306185730"

STATUS = {0:'已创建',1:'上传中',2:'已上传',3:'解析中',4:'已分析',5:'计划中',
          6:'已计划',7:'执行中',8:'已执行',9:'已完成',10:'失败',11:'回滚中',12:'已回滚'}

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
    return body["data"]

# ── Step 1: 登录 ──────────────────────────────────────────
log("STEP1", "登录获取 token ...")
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"},
                  timeout=10)
token = check(r, "登录")["token"]
log("STEP1", f"token={token[:16]}...  OK")
HDR = {"Authorization": token}

# ── Step 2: 确认任务为 ANALYZED(4) ────────────────────────
log("STEP2", f"查询任务 {TASK_ID} 当前状态 ...")
r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
task = check(r, "查询任务")
s = task["status"]
log("STEP2", f"status={STATUS.get(s, s)}  name={task['name']}  OK")
if s != 4:
    log("FAIL", f"任务状态不是 ANALYZED(4)，当前={s}，无法执行计划生成测试")
    sys.exit(1)

# ── Step 3: 触发 AI 迁移计划生成 ──────────────────────────
log("STEP3", "POST /migration/task/plan ...")
try:
    r = requests.post(f"{BASE}/migration/task/plan",
                      params={"taskId": TASK_ID},
                      headers=HDR,
                      timeout=300)
    data = check(r, "AI计划生成")
    log("STEP3", f"返回: {str(data)[:300]}  OK")
except Exception as e:
    log("STEP3", f"接口超时（后端仍处理中）: {str(e)[:100]}, 继续轮询...")

# ── Step 4: 轮询至 PLANNED(6) ─────────────────────────────
log("STEP4", "轮询等待 PLANNED(6) ...")
for i in range(60):
    time.sleep(5)
    r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
    task = check(r, "轮询")
    s = task["status"]
    log("STEP4", f"  [{i+1}] status={STATUS.get(s, s)}")
    if s == 6:
        log("STEP4", "计划生成完成 (PLANNED=6)  OK")
        break
    if s == 10:
        log("FAIL", "任务进入失败状态")
        sys.exit(1)
else:
    log("FAIL", "计划生成超时（5分钟未完成）")
    sys.exit(1)

# ── Step 5: 验证 migration_step 记录数 > 0 ────────────────
log("STEP5", "GET /migration/step/list ...")
r = requests.get(f"{BASE}/migration/step/list",
                 params={"taskId": TASK_ID},
                 headers=HDR, timeout=10)
steps = check(r, "查询步骤列表")
if not steps or len(steps) == 0:
    log("FAIL", "migration_step 记录数为 0，计划生成异常")
    sys.exit(1)
log("STEP5", f"步骤数={len(steps)}  OK")
for step in steps[:5]:
    log("STEP5", f"  #{step.get('stepNo')} [{step.get('stepType')}] {step.get('name')}")

# ── Step 6: 验证 migrationPlan 非空 ───────────────────────
log("STEP6", "验证 migrationPlan 字段 ...")
plan = task.get("migrationPlan") or ""
if not plan or len(plan) < 10:
    log("FAIL", f"migrationPlan 为空或过短: '{plan[:100]}'")
    sys.exit(1)
log("STEP6", f"migrationPlan 长度={len(plan)}  totalSteps={task.get('totalSteps',0)}  OK")

# ── 汇总 ──────────────────────────────────────────────────
print()
print("=" * 60)
print("  阶段6 迁移计划生成 E2E 测试：全部通过 ✓")
print(f"  任务ID       : {TASK_ID}")
print(f"  最终状态     : {STATUS.get(task['status'], task['status'])}")
print(f"  迁移步骤数   : {task.get('totalSteps', 0)}")
print(f"  migrationPlan: {len(plan)} 字符")
print(f"  前3步骤      :")
for step in steps[:3]:
    print(f"    #{step.get('stepNo')} [{step.get('stepType')}] {step.get('name')}")
print("=" * 60)
