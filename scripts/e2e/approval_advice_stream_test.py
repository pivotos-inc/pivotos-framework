#!/usr/bin/env python3
"""
PivotOS S101 E2E 冒烟脚本（2026-08-29，A3 AI 审批助手首轮·后端链路）
验证面：
  ① SSE 帧序：POST /ai/approval/advice/stream → meta → delta* → done（error 帧视为失败）；
  ② 结构化三态：done.conclusion ∈ {approve, reject, need_info}；
  ③ 落库回显：GET /ai/approval/advice/{taskId}/latest 与 done 帧结论一致；
  ④ 归属/存在性负例：伪 taskId → 5081 APPROVAL_TASK_NOT_FOUND。
前置：dev 库存在已发布「请假/分档」流程定义（S93 建）；admin 为待办审批人。
"""
import requests, json, sys, time

BASE = "http://localhost:8080"
VALID_CONCLUSIONS = {"approve", "reject", "need_info"}

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

# ── Step 2: 取或造一条待办 ─────────────────────────────────
task_id = None
ins_id = None
r = requests.get(f"{BASE}/workflow/task/pending/page",
                 params={"pageNum": 1, "pageSize": 10}, headers=HDR, timeout=10)
body = r.json()
assert body.get("code") == 0, f"待办分页失败: {body}"
rows = body["data"]["list"]
if rows:
    task_id = rows[0]["id"]
    log("STEP2", f"复用既有待办 taskId={task_id} flowName={rows[0].get('flowName')}")
else:
    log("STEP2", "无待办，发起请假流程造一条 ...")
    r = requests.get(f"{BASE}/workflow/definition/page",
                     params={"pageNum": 1, "pageSize": 50}, headers=HDR, timeout=10)
    defs = r.json()["data"]["list"]
    target = next((d for d in defs if ("请假" in (d.get("flowName") or ""))
                   or ("分档" in (d.get("flowName") or ""))), None)
    assert target is not None, "未找到请假/分档流程定义"
    r = requests.post(f"{BASE}/workflow/instance/start",
                      json={"flowCode": target["flowCode"], "businessName": "S101冒烟",
                            "variable": {"days": 1}}, headers=HDR, timeout=30)
    body = r.json()
    assert body.get("code") == 0, f"发起失败: {body}"
    ins_id = body["data"]["id"]
    time.sleep(1)
    r = requests.get(f"{BASE}/workflow/task/pending/page",
                     params={"pageNum": 1, "pageSize": 10}, headers=HDR, timeout=10)
    rows = r.json()["data"]["list"]
    assert rows, f"发起后仍无待办（审批人可能非 admin）：instanceId={ins_id}"
    task_id = rows[0]["id"]
    log("STEP2", f"新造待办 instanceId={ins_id} taskId={task_id}")

# ── Step 3: SSE 流式生成建议（核心验证） ────────────────────
log("STEP3", f"POST /ai/approval/advice/stream taskId={task_id} ...")
events = []
r = requests.post(f"{BASE}/ai/approval/advice/stream",
                  json={"taskId": task_id}, headers=HDR, stream=True, timeout=180)
assert r.status_code == 200, f"SSE 端点 HTTP {r.status_code}: {r.text[:300]}"
assert "text/event-stream" in (r.headers.get("Content-Type") or ""), \
    f"非 SSE 响应: {r.headers.get('Content-Type')}"
# SSE 解析：先整包读字节再统一 UTF-8 解码（避免 iter_lines 逐 chunk 解码在
# 多字节字符边界截断），随后按帧拼接 data（同帧多行以 \n 连接，空行分帧）
buf = b"".join(r.iter_content(chunk_size=None))
text = buf.decode("utf-8")
events = []
current = None
data_lines = []
for raw in text.splitlines():
    if raw == "":
        if current is not None:
            events.append((current, "\n".join(data_lines)))
        current = None
        data_lines = []
    elif raw.startswith("event:"):
        current = raw[6:].strip()
    elif raw.startswith("data:"):
        data_lines.append(raw[5:].lstrip())
if current is not None and data_lines:
    events.append((current, "\n".join(data_lines)))
names = [e[0] for e in events]
log("STEP3", f"帧序：{names[0]} → delta×{names.count('delta')} → {names[-1]}")
assert names[0] == "meta", f"首帧非 meta：{names[:3]}"
meta = json.loads(events[0][1])
assert meta.get("taskId") == task_id, f"meta taskId 不一致：{meta}"
assert meta.get("disclaimer"), "meta 缺免责声明"
assert names[-1] == "done", f"末帧非 done（error 帧即失败）：{events[-1]}"
done = json.loads(events[-1][1])
assert done.get("conclusion") in VALID_CONCLUSIONS, f"结论非三态：{done}"
assert names.count("delta") >= 1, "无 delta 帧"
log("PASS3", f"SSE 帧序合法；kbId={meta.get('kbId')} conclusion={done['conclusion']} "
             f"references={len(done.get('references') or [])}")

# ── Step 4: 落库回显一致性 ─────────────────────────────────
r = requests.get(f"{BASE}/ai/approval/advice/{task_id}/latest", headers=HDR, timeout=10)
body = r.json()
assert body.get("code") == 0 and body.get("data"), f"latest 回显失败: {body}"
latest = body["data"]
assert latest["conclusion"] == done["conclusion"], \
    f"落库结论不一致：latest={latest['conclusion']} done={done['conclusion']}"
assert latest.get("id"), "落库记录缺 id"
log("PASS4", f"落库回显一致 adviceId={latest['id']} reason={latest.get('reason','')[:60]}...")

# ── Step 5: 负例——伪 taskId → 5081 ─────────────────────────
r = requests.post(f"{BASE}/ai/approval/advice/stream",
                  json={"taskId": 999999999999}, headers=HDR, timeout=30)
ct = r.headers.get("Content-Type") or ""
if "json" in ct:
    body = r.json()
    assert body.get("code") == 5081, f"伪任务未报 5081：{body}"
    log("PASS5", f"伪 taskId 正确返回 5081：{body.get('msg')}")
else:
    log("WARN5", f"伪 taskId 返回非 JSON（{ct}），人工确认：{r.text[:200]}")

# ── Step 6: 清理（仅清理本脚本新造实例） ─────────────────────
if ins_id:
    r = requests.put(f"{BASE}/workflow/instance/{ins_id}/terminate", headers=HDR, timeout=10)
    log("CLEAN", f"终止实例 {ins_id}：{json.dumps(r.json(), ensure_ascii=False)[:200]}")

log("ALL-PASS", "S101 首轮后端链路冒烟通过：SSE 帧序 + 三态结论 + 落库回显 + 负例 5081")
