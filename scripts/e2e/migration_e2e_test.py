#!/usr/bin/env python3
"""
PivotOS 系统迁移功能 E2E 自测脚本
使用「自测任务」(7132 个已索引文件，状态=已上传) 跑 解析 → AI分析 全链路
"""
import requests, json, time, sys

BASE = "http://localhost:8080"
TASK_ID = "2089577593306185730"  # 自测任务，已上传 7132 文件

# ── 工具函数 ──────────────────────────────────────────────
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

# ── Step 2: 查询任务当前状态 ──────────────────────────────
log("STEP2", f"查询任务 {TASK_ID} 当前状态 ...")
r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
task = check(r, "查询任务")
STATUS = {0:'已创建',1:'上传中',2:'已上传',3:'解析中',4:'已分析',5:'计划中',6:'已计划',7:'执行中',8:'已执行',9:'已完成',10:'失败',11:'回滚中',12:'已回滚'}
log("STEP2", f"name={task['name']}  status={STATUS.get(task['status'], task['status'])}  OK")

# ── Step 3: 触发解析 ──────────────────────────────────────
log("STEP3", "触发解析 POST /migration/task/parse ...")
try:
    r = requests.post(f"{BASE}/migration/task/parse",
                      params={"taskId": TASK_ID},
                      headers=HDR,
                      timeout=600)  # 7132 个文件需要更长时间
    data = check(r, "触发解析")
    log("STEP3", f"解析返回: {json.dumps(data, ensure_ascii=False)[:200]}  OK")
except Exception as e:
    # 同步接口超时属正常（后端仍在处理），继续轮询状态
    log("STEP3", f"接口超时（后端仍处理中）: {str(e)[:100]}, 继续轮询...")

# ── Step 4: 轮询至 PARSED(4) ─────────────────────────────
log("STEP4", "轮询任务状态，等待解析完成 ...")
for i in range(120):  # 最多轮询 120 次，每次 10s，共 20 分钟
    time.sleep(10)
    r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
    task = check(r, "轮询任务")
    s = task["status"]
    log("STEP4", f"  [{i+1}] status={STATUS.get(s, s)}")
    if s == 4:
        log("STEP4", "解析完成 (PARSED=4)  OK")
        break
    if s == 10:
        log("FAIL", "任务进入失败状态")
        sys.exit(1)
else:
    log("FAIL", "解析超时（90s 未完成）")
    sys.exit(1)

# ── Step 5: 触发 AI 架构分析 ─────────────────────────────
log("STEP5", "触发 AI 架构分析 POST /migration/task/analyze ...")
r = requests.post(f"{BASE}/migration/task/analyze",
                  params={"taskId": TASK_ID},
                  headers=HDR,
                  timeout=300)
data = check(r, "AI分析")
log("STEP5", f"分析返回: {str(data)[:300]}  OK")

# ── Step 6: 验证 analysisReport 写入 ─────────────────────
log("STEP6", "验证 analysisReport 字段 ...")
r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
task = check(r, "验证任务")
report = task.get("analysisReport") or ""
if not report or len(report) < 50:
    log("FAIL", f"analysisReport 为空或过短：'{report[:100]}'")
    sys.exit(1)
log("STEP6", f"analysisReport 长度={len(report)}  前100字符：{report[:100]}  OK")

# ── Step 7: 触发 AI 迁移计划生成 ──────────────────────────
log("STEP7", "触发 AI 计划生成 POST /migration/task/plan ...")
try:
    r = requests.post(f"{BASE}/migration/task/plan",
                      params={"taskId": TASK_ID},
                      headers=HDR,
                      timeout=300)
    data = check(r, "AI计划生成")
    log("STEP7", f"计划生成返回: {str(data)[:300]}  OK")
except Exception as e:
    log("STEP7", f"接口超时（后端仍处理中）: {str(e)[:100]}, 继续轮询...")

# ── Step 8: 轮询至 PLANNED(6) ────────────────────────────
log("STEP8", "轮询任务状态，等待计划生成完成 ...")
for i in range(60):  # 最多 10 分钟
    time.sleep(10)
    r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
    task = check(r, "轮询任务")
    s = task["status"]
    log("STEP8", f"  [{i+1}] status={STATUS.get(s, s)}")
    if s == 6:
        log("STEP8", "计划生成完成 (PLANNED=6)  OK")
        break
    if s == 10:
        log("FAIL", "任务进入失败状态")
        sys.exit(1)
else:
    log("FAIL", "计划生成超时（10 分钟未完成）")
    sys.exit(1)

# ── Step 9: 验证 migration_step 记录 ──────────────────────
log("STEP9", "验证 migration_step 记录数 ...")
r = requests.get(f"{BASE}/migration/step/list",
                 params={"taskId": TASK_ID},
                 headers=HDR, timeout=10)
steps = check(r, "查询步骤列表")
if not steps or len(steps) == 0:
    log("FAIL", "migration_step 记录数为 0，计划生成异常")
    sys.exit(1)
log("STEP9", f"migration_step 记录数={len(steps)}  OK")
for step in steps[:5]:
    log("STEP9", f"  stepNo={step.get('stepNo')} type={step.get('stepType')} name={step.get('name')}")

# ── Step 10: 验证 migration_plan 写入 ────────────────────
log("STEP10", "验证 migrationPlan 字段 ...")
r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
task = check(r, "验证计划任务")
plan = task.get("migrationPlan") or ""
if not plan or len(plan) < 10:
    log("FAIL", f"migrationPlan 为空或过短：'{plan[:100]}'")
    sys.exit(1)
log("STEP10", f"migrationPlan 长度={len(plan)}  总步骤数={task.get('totalSteps', 0)}  OK")

# ── 汇总 ──────────────────────────────────────────────────
print()
print("=" * 60)
print("  E2E 测试结果：全部通过 √")
print(f"  任务ID       : {TASK_ID}")
print(f"  最终状态     : {STATUS.get(task['status'], task['status'])}")
print(f"  分析报告长   : {len(report)} 字符")
print(f"  迁移计划长   : {len(plan)} 字符")
print(f"  迁移步骤数   : {task.get('totalSteps', 0)}")
print("=" * 60)
