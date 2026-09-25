#!/usr/bin/env python3
"""
PivotOS L2 清偿 E2E 自测脚本（2026-08-23 第 4 轮）
现象背景（S93 遗留 L2）：排他网关条件全不匹配时发起流程，引擎抛 FlowException
未被服务层捕获翻译，落入全局兜底报 1500「系统内部错误」。
修复：FlowInstanceService.start() catch FlowException → ServiceException 友好文案。
验证：① 无变量发起条件分支流程 → 返回业务文案（非 1500 系统内部错误）；
      ② 匹配变量发起 → 成功（回归确认未破坏正常链路），实例随即终止清理。
前置：dev 库存在带排他网关条件的已发布流程定义（S93 save-json 建的「请假分档审批」类）。
"""
import requests, json, sys

BASE = "http://localhost:8080"

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

# ── Step 1: 登录 ──────────────────────────────────────────
log("STEP1", "登录获取 token ...")
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
body = r.json()
assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
token = body["data"]["token"]
HDR = {"Authorization": token}
log("STEP1", f"token={token[:16]}...  OK")

# ── Step 2: 找带条件分支的流程定义 ─────────────────────────
log("STEP2", "分页查询流程定义，定位条件分支流程 ...")
r = requests.get(f"{BASE}/workflow/definition/page",
                 params={"pageNum": 1, "pageSize": 50}, headers=HDR, timeout=10)
body = r.json()
assert body.get("code") == 0, f"定义分页失败: {body}"
defs = body["data"]["list"]
target = None
for d in defs:
    name = d.get("flowName") or ""
    if "请假" in name or "分档" in name:
        target = d
        break
if target is None:
    log("FAIL", "未找到条件分支流程定义（请假/分档），请确认 S93 建过的定义仍在 dev 库")
    sys.exit(1)
flow_code = target["flowCode"]
log("STEP2", f"命中定义：flowName={target.get('flowName')} flowCode={flow_code} "
            f"publish={target.get('publishStatus') or target.get('isPublish')}")

# ── Step 3: 无变量发起 → 条件全不匹配（L2 核心验证） ──────
log("STEP3", "无变量发起（网关条件应全不匹配）...")
r = requests.post(f"{BASE}/workflow/instance/start",
                  json={"flowCode": flow_code, "variable": {}}, headers=HDR, timeout=30)
body = r.json()
log("STEP3", f"HTTP {r.status_code} body={json.dumps(body, ensure_ascii=False)[:300]}")
code = body.get("code")
msg = body.get("msg") or ""
assert code != 0, "期望发起失败但成功了——条件分支语义可能被破坏"
# 口径：ServiceException(String) 默认 code=1500 是全项目业务异常统一码（既有基线，
# FlowTaskService 六处翻译同口径）；L2 判定标准是文案不再是「系统内部错误」而是业务提示。
assert "系统内部错误" not in msg, f"L2 未清偿：文案仍为系统内部错误（{msg}）"
assert ("发起失败" in msg) or ("条件" in msg), f"文案非预期业务提示：{msg}"
log("PASS3", f"L2 清偿生效：业务异常（code={code} 为 ServiceException 统一码），文案=「{msg}」")

# ── Step 4: 匹配变量发起 → 回归确认成功链路未破坏 ─────────
log("STEP4", "days=1 发起（应匹配 lt 条件分支）...")
r = requests.post(f"{BASE}/workflow/instance/start",
                  json={"flowCode": flow_code, "businessName": "L2回归用例",
                        "variable": {"days": 1}}, headers=HDR, timeout=30)
body = r.json()
assert body.get("code") == 0, f"回归失败：匹配变量发起被阻断 {json.dumps(body, ensure_ascii=False)[:300]}"
ins_id = body["data"]["id"]
log("PASS4", f"发起成功 instanceId={ins_id}（正常链路未破坏）")

# ── Step 5: 清理——终止实例 ────────────────────────────────
r = requests.put(f"{BASE}/workflow/instance/{ins_id}/terminate",
                 headers=HDR, timeout=10)
log("CLEAN", f"终止实例 {ins_id}：{json.dumps(r.json(), ensure_ascii=False)[:200]}")

log("ALL-PASS", "L2 清偿验证完成：失败文案已翻译 + 成功链路回归通过")
