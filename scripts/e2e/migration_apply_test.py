#!/usr/bin/env python3
"""
阶段8 产物落盘、回滚与任务完成 E2E 测试
前置：自测任务 EXECUTING(7)，步骤1 COMPLETED(6)，步骤2 SELF_TEST_PASSED(2)
流程：
  1. 评审通过步骤2 → COMPLETED(6)
  2. 批量落盘步骤2产物 → 验证文件真实存在且 applied=true
  3. 撤销单个产物 → 验证文件删除、applied=false
  4. 重新落盘单个产物 → 验证文件恢复
  5. 路径穿越防护：直连 DB 插入 relativePath 含 ../ 的产物，apply 应被拒绝
  6. complete/rollback 非法状态调用被拒
"""
import requests, json, time, sys, os

BASE = "http://localhost:8080"
TASK_ID = "2089577593306185730"
WORKSPACE = "/Users/huweilong/Documents/File/Project/PivotOS Technology/PivotOS/pivotos-framework/pivotos-admin-server/data/migration"
TARGET_ROOT = os.path.join(WORKSPACE, TASK_ID, "target")

DB = dict(host='175.24.176.176', port=3306, user='root',
          password='mysql_DbHEfw', database='pivotos', charset='utf8mb4')

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

def check(r, step, expect_fail=False):
    if r.status_code != 200:
        log("FAIL", f"{step} HTTP {r.status_code}: {r.text[:300]}")
        sys.exit(1)
    body = r.json()
    ok = body.get("success") and body.get("code") == 0
    if expect_fail:
        if ok:
            log("FAIL", f"{step} 应当被拒绝但成功了: {json.dumps(body, ensure_ascii=False)[:200]}")
            sys.exit(1)
        log("OK", f"{step} 按预期被拒绝: {body.get('msg','')[:80]}")
        return None
    if not ok:
        log("FAIL", f"{step} 业务失败: {json.dumps(body, ensure_ascii=False)[:300]}")
        sys.exit(1)
    return body.get("data")

# ── Step 1: 登录 ─────────────────────────────────────────────
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=10)
token = check(r, "登录")["token"]
HDR = {"Authorization": token}
log("STEP1", "登录成功")

# ── Step 2: 确认任务状态 & 步骤2状态 ─────────────────────────
r = requests.get(f"{BASE}/migration/task/{TASK_ID}", headers=HDR, timeout=10)
task = check(r, "任务详情")
log("STEP2", f"任务状态={task['status']}, completedSteps={task.get('completedSteps')}/{task.get('totalSteps')}")
assert task["status"] == 7, f"预期 EXECUTING(7)，实际 {task['status']}"

r = requests.get(f"{BASE}/migration/step/list", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
steps = check(r, "步骤列表")
step2 = steps[1]
log("STEP2", f"步骤2: id={step2['id']}, name={step2['name']}, status={step2['status']}")

# ── Step 3: 评审通过步骤2（幂等） ────────────────────────────
if step2["status"] == 2:
    r = requests.post(f"{BASE}/migration/step/review",
                      params={"stepId": step2["id"], "action": "PASS", "comment": "E2E 自动评审"},
                      headers=HDR, timeout=30)
    check(r, "评审步骤2")
    log("STEP3", "步骤2 评审通过")
elif step2["status"] == 6:
    log("STEP3", "步骤2 已完成，跳过评审")
else:
    log("FAIL", f"步骤2 状态 {step2['status']} 无法评审，请先执行步骤2")
    sys.exit(1)

# ── Step 4: 批量落盘步骤2产物 ────────────────────────────────
r = requests.post(f"{BASE}/migration/artifact/apply-step",
                  params={"stepId": step2["id"]}, headers=HDR, timeout=60)
count = check(r, "批量落盘")
count = int(count)
log("STEP4", f"批量落盘成功，产物数={count}")
assert count and count > 0, "落盘产物数应 > 0"

r = requests.get(f"{BASE}/migration/artifact/list", params={"stepId": step2["id"]}, headers=HDR, timeout=10)
artifacts = check(r, "产物列表")
assert len(artifacts) == count, f"产物数不一致: {len(artifacts)} vs {count}"

# 验证文件真实存在且 applied=true
for a in artifacts:
    assert a["applied"], f"产物 {a['relativePath']} applied 应为 true"
    fpath = os.path.join(TARGET_ROOT, a["relativePath"])
    assert os.path.isfile(fpath), f"落盘文件不存在: {fpath}"
    assert os.path.getsize(fpath) > 0, f"落盘文件为空: {fpath}"
log("STEP4", f"全部 {count} 个文件真实存在且 applied=true ✓")

# ── Step 5: 撤销单个产物 ─────────────────────────────────────
first = artifacts[0]
fpath = os.path.join(TARGET_ROOT, first["relativePath"])
r = requests.post(f"{BASE}/migration/artifact/unapply",
                  params={"artifactId": first["id"]}, headers=HDR, timeout=30)
check(r, "撤销落盘")
assert not os.path.exists(fpath), f"撤销后文件仍存在: {fpath}"
r = requests.get(f"{BASE}/migration/artifact/{first['id']}", headers=HDR, timeout=10)
detail = check(r, "产物详情")
assert detail["applied"] is False, "撤销后 applied 应为 false"
log("STEP5", f"撤销成功，文件已删除，applied=false ✓ ({first['relativePath']})")

# ── Step 6: 重新落盘单个产物 ─────────────────────────────────
r = requests.post(f"{BASE}/migration/artifact/apply",
                  params={"artifactId": first["id"]}, headers=HDR, timeout=30)
check(r, "重新落盘")
assert os.path.isfile(fpath), f"重新落盘后文件不存在: {fpath}"
log("STEP6", "重新落盘成功，文件恢复 ✓")

# ── Step 7: 路径穿越防护 ─────────────────────────────────────
import pymysql
conn = pymysql.connect(**DB)
cur = conn.cursor()
evil_id = int(time.time() * 1000)
cur.execute(
    "INSERT INTO migration_artifact (id, task_id, step_id, artifact_type, relative_path, "
    "generated_content, content_hash, applied, create_time) "
    "VALUES (%s, %s, %s, 'OTHER', %s, 'evil', 'x', 0, NOW())",
    (evil_id, TASK_ID, step2["id"], "../../../../tmp/migration_evil_test.txt"))
conn.commit()

r = requests.post(f"{BASE}/migration/artifact/apply",
                  params={"artifactId": evil_id}, headers=HDR, timeout=30)
check(r, "路径穿越 apply", expect_fail=True)
assert not os.path.exists("/tmp/migration_evil_test.txt"), "路径穿越文件被写出了！"

cur.execute("DELETE FROM migration_artifact WHERE id = %s", (evil_id,))
conn.commit()
conn.close()
log("STEP7", "路径穿越被拦截，恶意记录已清理 ✓")

# ── Step 8: complete/rollback 非法状态校验 ───────────────────
r = requests.get(f"{BASE}/migration/task/page", params={"pageNum": 1, "pageSize": 50}, headers=HDR, timeout=10)
page = check(r, "任务分页")
other = next((t for t in page["list"] if t["id"] != TASK_ID and t["status"] not in (7, 8)), None)
if other:
    r = requests.post(f"{BASE}/migration/task/complete", params={"taskId": other["id"]}, headers=HDR, timeout=10)
    check(r, f"complete 非法状态({other['status']})", expect_fail=True)
    r = requests.post(f"{BASE}/migration/task/rollback", params={"taskId": other["id"]}, headers=HDR, timeout=10)
    check(r, f"rollback 非法状态({other['status']})", expect_fail=True)
else:
    # 无其他任务：对 EXECUTING 任务调用 complete（非 EXECUTED 应被拒）
    r = requests.post(f"{BASE}/migration/task/complete", params={"taskId": TASK_ID}, headers=HDR, timeout=10)
    check(r, "complete 非法状态(EXECUTING)", expect_fail=True)
log("STEP8", "状态白名单校验通过 ✓")

log("DONE", "═══ 阶段8 E2E 全部通过 ═══")
