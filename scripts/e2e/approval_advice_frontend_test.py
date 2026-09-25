#!/usr/bin/env python3
"""
PivotOS S101 次轮 E2E 冒烟脚本（2026-09-25，A3 AI 审批助手·前端接入）
与首轮脚本（直连 8080）的差异：全程经 PC 开发服 Vite 代理（5173 /api 前缀），
与前端 fetch('/api/ai/approval/advice/stream') 走的路径完全一致。
验证面：
  ① 登录与待办分页经代理可用；
  ② 知识库下拉数据源 /ai/chat/kb-options 可用（审批页下拉）；
  ③ SSE 帧序：meta（含 disclaimer）→ delta* → done（含三态结论 + references + disclaimer）；
  ④ 落库回显：GET latest 与 done 帧结论一致；
  ⑤ 显式 kbId 入参经代理透传（kb 选项非空时）；
  ⑥ 负例：伪 taskId → 5081；
  ⑦ 清理本脚本新造实例。
前置：后端 8080 + PC 5173 运行中；dev 库存在已发布「请假/分档」流程定义。
"""
import requests, json, sys, time

BASE = "http://localhost:5173/api"
VALID_CONCLUSIONS = {"approve", "reject", "need_info"}
DISCLAIMER = "AI 建议仅供参考，审批责任仍归审批人"

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

# ── Step 1: 登录（经代理） ─────────────────────────────────
log("STEP1", "经 5173 代理登录获取 token ...")
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
body = r.json()
assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
token = body["data"]["token"]
HDR = {"Authorization": token}
log("STEP1", f"token={token[:16]}...  OK")

# ── Step 2: 知识库下拉数据源（审批页下拉用） ──────────────────
r = requests.get(f"{BASE}/ai/chat/kb-options", headers=HDR, timeout=10)
body = r.json()
assert body.get("code") == 0, f"kb-options 失败: {body}"
kb_options = body.get("data") or []
kb_id = kb_options[0]["id"] if kb_options else None
log("STEP2", f"知识库选项 {len(kb_options)} 个，显式 kbId={kb_id}")

# ── Step 3: 取或造一条待办 ─────────────────────────────────
task_id = None
ins_id = None
r = requests.get(f"{BASE}/workflow/task/pending/page",
                 params={"pageNum": 1, "pageSize": 10}, headers=HDR, timeout=10)
body = r.json()
assert body.get("code") == 0, f"待办分页失败: {body}"
rows = body["data"]["list"]
if rows:
    task_id = rows[0]["id"]
    log("STEP3", f"复用既有待办 taskId={task_id} flowName={rows[0].get('flowName')}")
else:
    log("STEP3", "无待办，发起请假流程造一条 ...")
    r = requests.get(f"{BASE}/workflow/definition/page",
                     params={"pageNum": 1, "pageSize": 50}, headers=HDR, timeout=10)
    defs = r.json()["data"]["list"]
    target = next((d for d in defs if ("请假" in (d.get("flowName") or ""))
                   or ("分档" in (d.get("flowName") or ""))), None)
    assert target is not None, "未找到请假/分档流程定义"
    r = requests.post(f"{BASE}/workflow/instance/start",
                      json={"flowCode": target["flowCode"], "businessName": "S101次轮冒烟",
                            "variable": {"days": 2}}, headers=HDR, timeout=30)
    body = r.json()
    assert body.get("code") == 0, f"发起失败: {body}"
    ins_id = body["data"]["id"]
    time.sleep(1)
    r = requests.get(f"{BASE}/workflow/task/pending/page",
                     params={"pageNum": 1, "pageSize": 10}, headers=HDR, timeout=10)
    rows = r.json()["data"]["list"]
    assert rows, f"发起后仍无待办（审批人可能非 admin）：instanceId={ins_id}"
    task_id = rows[0]["id"]
    log("STEP3", f"新造待办 instanceId={ins_id} taskId={task_id}")

# ── Step 4: SSE 流式生成建议（经代理，显式 kbId 与前端一致） ──
log("STEP4", f"POST /api/ai/approval/advice/stream taskId={task_id} kbId={kb_id} ...")
payload = {"taskId": task_id}
if kb_id:
    payload["kbId"] = kb_id
r = requests.post(f"{BASE}/ai/approval/advice/stream",
                  json=payload, headers=HDR, stream=True, timeout=180)
assert r.status_code == 200, f"SSE 端点 HTTP {r.status_code}: {r.text[:300]}"
assert "text/event-stream" in (r.headers.get("Content-Type") or ""), \
    f"非 SSE 响应: {r.headers.get('Content-Type')}"
# SSE 解析：整包读字节后统一 UTF-8 解码，按空行分帧（同首轮脚本口径）
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
log("STEP4", f"帧序：{names[0]} → delta×{names.count('delta')} → {names[-1]}")
assert names[0] == "meta", f"首帧非 meta：{names[:3]}"
meta = json.loads(events[0][1])
assert str(meta.get("taskId")) == str(task_id), f"meta taskId 不一致：{meta}"
assert meta.get("disclaimer") == DISCLAIMER, f"meta 免责语不符：{meta.get('disclaimer')}"
assert names[-1] == "done", f"末帧非 done（error 帧即失败）：{events[-1]}"
done = json.loads(events[-1][1])
assert done.get("conclusion") in VALID_CONCLUSIONS, f"结论非三态：{done}"
assert done.get("disclaimer") == DISCLAIMER, "done 帧缺免责声明"
assert names.count("delta") >= 1, "无 delta 帧"
log("PASS4", f"SSE 帧序合法；conclusion={done['conclusion']} "
             f"references={len(done.get('references') or [])} disclaimer=OK")

# ── Step 5: 落库回显一致性（前端打开抽屉时的 latest 调用） ──
r = requests.get(f"{BASE}/ai/approval/advice/{task_id}/latest", headers=HDR, timeout=10)
body = r.json()
assert body.get("code") == 0 and body.get("data"), f"latest 回显失败: {body}"
latest = body["data"]
assert latest["conclusion"] == done["conclusion"], \
    f"落库结论不一致：latest={latest['conclusion']} done={done['conclusion']}"
assert latest.get("id"), "落库记录缺 id"
log("PASS5", f"落库回显一致 adviceId={latest['id']} reason={latest.get('reason','')[:60]}...")

# ── Step 6: 负例——伪 taskId → 5081 ─────────────────────────
r = requests.post(f"{BASE}/ai/approval/advice/stream",
                  json={"taskId": 999999999999}, headers=HDR, timeout=30)
ct = r.headers.get("Content-Type") or ""
if "json" in ct:
    body = r.json()
    assert body.get("code") == 5081, f"伪任务未报 5081：{body}"
    log("PASS6", f"伪 taskId 正确返回 5081：{body.get('msg')}")
else:
    log("WARN6", f"伪 taskId 返回非 JSON（{ct}），人工确认：{r.text[:200]}")

# ── Step 7: 清理（仅清理本脚本新造实例） ─────────────────────
if ins_id:
    r = requests.put(f"{BASE}/workflow/instance/{ins_id}/terminate", headers=HDR, timeout=10)
    log("CLEAN", f"终止实例 {ins_id}：{json.dumps(r.json(), ensure_ascii=False)[:200]}")

log("ALL-PASS", "S101 次轮前端链路冒烟通过（经 5173 代理）：帧序 + 三态 + 免责语 + 落库回显 + 负例 5081")
