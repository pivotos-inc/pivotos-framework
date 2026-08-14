#!/bin/bash
# 流程管理完整端到端测试脚本
# 流程定义: leave-approval v4 (开始→提交申请→主管审批→结束, 含REJECT路径)
# 前置条件: 后端已启动，最新定义已发布
set -e
BASE="http://localhost:8080"
login() { /usr/bin/curl -s "$BASE/system/auth/login" -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | /usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])"; }
api() {
  local method="$1" path="$2" data="$3"
  if [ -n "$data" ]; then /usr/bin/curl -s -X "$method" "$BASE$path" -H "Authorization: $TOKEN" -H "Content-Type: application/json" -d "$data"
  else /usr/bin/curl -s -X "$method" "$BASE$path" -H "Authorization: $TOKEN"; fi
}
PASS=0; FAIL=0
check() {
  local name="$1" resp="$2" expect_code="${3:-0}"
  local code=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin).get('code',-1))" <<< "$resp")
  if [ "$code" = "$expect_code" ]; then echo "  ✅ $name"; PASS=$((PASS+1))
  else echo "  ❌ $name (code=$code expect=$expect_code)"; FAIL=$((FAIL+1)); fi
}
echo "=== 流程管理完整测试 (leave-approval v4) ==="
TOKEN=$(login)
echo "Token OK"

echo "--- T1: 定义管理 ---"
LATEST=$(api GET "/workflow/definition/page?pageNum=1&pageSize=1")
DEF_ID=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['list'][0]['id'])" <<< "$LATEST")
echo "  defId=$DEF_ID"
check "T1.1 发布" "$(api PUT /workflow/definition/$DEF_ID/publish)"
check "T1.2 详情" "$(api GET /workflow/definition/$DEF_ID)"
check "T1.3 挂起" "$(api PUT /workflow/definition/$DEF_ID/toggle-activity)"
check "T1.4 激活" "$(api PUT /workflow/definition/$DEF_ID/toggle-activity)"

echo "--- T2: 发起实例 ---"
R=$(api POST "/workflow/instance/start" '{"flowCode":"leave-approval","businessId":"E2E-001"}')
check "T2.1 发起" "$R"
INST=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" <<< "$R")
check "T2.2 待办" "$(api GET /workflow/task/pending/page?pageNum=1&pageSize=10)"
# 通过apply节点
TASK_APPLY=$(/usr/bin/python3 -c "import sys,json;d=json.load(sys.stdin)['data']['list']
for t in d:
  if str(t['instanceId'])=='$INST': print(t['id']); break" <<< "$(api GET /workflow/task/pending/page?pageNum=1&pageSize=10)")
check "T2.3 通过apply" "$(api PUT /workflow/task/pass "{\"taskId\":$TASK_APPLY,\"message\":\"提交\"}")"
check "T2.4 我的实例" "$(api GET /workflow/instance/page?pageNum=1&pageSize=10)"
check "T2.5 实例详情" "$(api GET /workflow/instance/$INST)"

echo "--- T3: 审批通过 ---"
TASK_REVIEW=$(/usr/bin/python3 -c "import sys,json;d=json.load(sys.stdin)['data']['list']
for t in d:
  if str(t['instanceId'])=='$INST': print(t['id']); break" <<< "$(api GET /workflow/task/pending/page?pageNum=1&pageSize=10)")
check "T3.1 通过" "$(api PUT /workflow/task/pass "{\"taskId\":$TASK_REVIEW,\"message\":\"同意\"}")"
R=$(api GET /workflow/instance/$INST)
STATUS=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['flowStatus'])" <<< "$R")
[ "$STATUS" = "8" ] && echo "  ✅ T3.2 完成(flowStatus=8)" && PASS=$((PASS+1)) || (echo "  ❌ T3.2 状态=$STATUS" && FAIL=$((FAIL+1)))
check "T3.3 已办" "$(api GET /workflow/task/completed/page?pageNum=1&pageSize=10)"
check "T3.4 历史" "$(api GET /workflow/task/history/$INST)"

echo "--- T4: 驳回 + 重新审批 ---"
R=$(api POST "/workflow/instance/start" '{"flowCode":"leave-approval","businessId":"E2E-002"}')
INST4=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" <<< "$R")
# 通过apply
T4A=$(/usr/bin/python3 -c "import sys,json;d=json.load(sys.stdin)['data']['list']
for t in d:
  if str(t['instanceId'])=='$INST4': print(t['id']); break" <<< "$(api GET /workflow/task/pending/page?pageNum=1&pageSize=10)")
api PUT /workflow/task/pass "{\"taskId\":$T4A,\"message\":\"提交\"}" > /dev/null
# 驳回review
T4R=$(/usr/bin/python3 -c "import sys,json;d=json.load(sys.stdin)['data']['list']
for t in d:
  if str(t['instanceId'])=='$INST4': print(t['id']); break" <<< "$(api GET /workflow/task/pending/page?pageNum=1&pageSize=10)")
check "T4.1 驳回" "$(api PUT /workflow/task/reject "{\"taskId\":$T4R,\"message\":\"太多\"}")"
R=$(api GET /workflow/instance/$INST4)
STATUS4=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['flowStatus'])" <<< "$R")
[ "$STATUS4" = "9" ] && echo "  ✅ T4.2 已退回(flowStatus=9)" && PASS=$((PASS+1)) || (echo "  ❌ T4.2 状态=$STATUS4" && FAIL=$((FAIL+1)))
# 重新提交+审批
T4A2=$(/usr/bin/python3 -c "import sys,json;d=json.load(sys.stdin)['data']['list']
for t in d:
  if str(t['instanceId'])=='$INST4': print(t['id']); break" <<< "$(api GET /workflow/task/pending/page?pageNum=1&pageSize=10)")
check "T4.3 重新提交" "$(api PUT /workflow/task/pass "{\"taskId\":$T4A2,\"message\":\"重新提交\"}")"
T4R2=$(/usr/bin/python3 -c "import sys,json;d=json.load(sys.stdin)['data']['list']
for t in d:
  if str(t['instanceId'])=='$INST4': print(t['id']); break" <<< "$(api GET /workflow/task/pending/page?pageNum=1&pageSize=10)")
check "T4.4 重新审批" "$(api PUT /workflow/task/pass "{\"taskId\":$T4R2,\"message\":\"批准\"}")"
R=$(api GET /workflow/instance/$INST4)
STATUS4F=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['flowStatus'])" <<< "$R")
[ "$STATUS4F" = "8" ] && echo "  ✅ T4.5 驳回后完成(flowStatus=8)" && PASS=$((PASS+1)) || (echo "  ❌ T4.5 状态=$STATUS4F" && FAIL=$((FAIL+1)))
check "T4.6 驳回历史" "$(api GET /workflow/task/history/$INST4)"

echo "--- T5: 撤回/终止 ---"
R=$(api POST "/workflow/instance/start" '{"flowCode":"leave-approval","businessId":"E2E-003"}')
INST5=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" <<< "$R")
check "T5.1 撤回" "$(api PUT /workflow/instance/$INST5/revoke)"
R=$(api GET /workflow/instance/$INST5)
STATUS5=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['flowStatus'])" <<< "$R")
[ "$STATUS5" = "6" ] && echo "  ✅ T5.1b 已撤回(flowStatus=6)" && PASS=$((PASS+1)) || (echo "  ❌ T5.1b 状态=$STATUS5" && FAIL=$((FAIL+1)))
R=$(api POST "/workflow/instance/start" '{"flowCode":"leave-approval","businessId":"E2E-004"}')
INST6=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" <<< "$R")
check "T5.2 终止" "$(api PUT /workflow/instance/$INST6/terminate)"
R=$(api GET /workflow/instance/$INST6)
STATUS6=$(/usr/bin/python3 -c "import sys,json; print(json.load(sys.stdin)['data']['flowStatus'])" <<< "$R")
[ "$STATUS6" = "4" ] && echo "  ✅ T5.2b 已终止(flowStatus=4)" && PASS=$((PASS+1)) || (echo "  ❌ T5.2b 状态=$STATUS6" && FAIL=$((FAIL+1)))

echo "=== 结果: PASS=$PASS FAIL=$FAIL ==="
