#!/usr/bin/env python3
"""
PivotOS S99 A2 首批业务工具 + REST/MCP 双入口主 E2E（2026-08-23 第 5 轮）
链路：注册表同步 8 工具 → REST 直连只读三工具（与 REST 业务基线对账）
     → MCP 双暴露一致性（tools/list 集合一致 + 调用结果一致）
     → 写操作二次确认（预检拦截 + confirm=true 放行 + 副作用实证）→ 审计落库 → 未注册工具拒绝
"""
import requests, json, threading, queue, time

BASE = "http://localhost:8080"

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

class McpSession:
    """MCP SSE 会话（字节层手工分帧，S97 K1 口径）"""
    def __init__(self, headers):
        self.frames = queue.Queue()
        self.endpoint = None
        self.headers = headers
        threading.Thread(target=self._reader, daemon=True).start()
        deadline = time.time() + 15
        while time.time() < deadline and not self.endpoint:
            time.sleep(0.2)
        assert self.endpoint, "SSE 未在超时内下发 endpoint 事件"
        self.msg_url = BASE + (self.endpoint if self.endpoint.startswith("/") else "/" + self.endpoint)
        self.seq = 0

    def _reader(self):
        with requests.get(f"{BASE}/sse", stream=True, timeout=(10, 300), headers=self.headers) as resp:
            assert resp.status_code == 200, f"SSE 连接失败 HTTP {resp.status_code}"
            buf = b""
            for chunk in resp.iter_content(chunk_size=1024):
                if not chunk:
                    continue
                buf += chunk
                while True:
                    idx, crlf = buf.find(b"\n\n"), buf.find(b"\r\n\r\n")
                    if idx < 0 and crlf < 0:
                        break
                    if crlf >= 0 and (idx < 0 or crlf < idx):
                        raw, sep = buf[:crlf], 4
                    else:
                        raw, sep = buf[:idx], 2
                    buf = buf[len(raw) + sep:]
                    event, data = None, []
                    for line in raw.decode("utf-8", errors="replace").split("\n"):
                        line = line.rstrip("\r")
                        if line.startswith("event:"):
                            event = line[6:].strip()
                        elif line.startswith("data:"):
                            data.append(line[5:].strip())
                    if event == "endpoint":
                        self.endpoint = "\n".join(data)
                    elif data:
                        self.frames.put("\n".join(data))

    def post(self, payload):
        r = requests.post(self.msg_url, json=payload, headers=self.headers, timeout=30)
        assert r.status_code in (200, 202), f"MCP POST 失败 HTTP {r.status_code}: {r.text[:200]}"

    def call(self, method, params=None):
        self.seq += 1
        msg_id = 500 + self.seq
        payload = {"jsonrpc": "2.0", "id": msg_id, "method": method}
        if params is not None:
            payload["params"] = params
        self.post(payload)
        deadline = time.time() + 30
        stash = []
        try:
            while time.time() < deadline:
                try:
                    data = self.frames.get(timeout=1)
                except queue.Empty:
                    continue
                try:
                    obj = json.loads(data)
                except json.JSONDecodeError:
                    continue
                if obj.get("id") == msg_id:
                    return obj
                stash.append(data)
            raise AssertionError(f"超时未收到 id={msg_id} 响应帧")
        finally:
            for item in reversed(stash):
                self.frames.queue.appendleft(item)

    def tool_call_text(self, name, arguments):
        resp = self.call("tools/call", {"name": name, "arguments": arguments})
        content = resp.get("result", {}).get("content", [])
        return content[0].get("text", "") if content else ""

def rest_call(name, args):
    r = requests.post(f"{BASE}/ai/tool/call", json={"toolName": name, "args": args},
                      headers=HDR, timeout=60)
    body = r.json()
    assert body.get("code") == 0, f"REST 调用 {name} 失败: {json.dumps(body, ensure_ascii=False)[:300]}"
    return body["data"]

def audit_total(tool_name, status=None):
    params = {"pageNum": 1, "pageSize": 100, "toolName": tool_name}
    if status:
        params["invokeStatus"] = status
    r = requests.get(f"{BASE}/ai/tool/invoke/page", params=params, headers=HDR, timeout=15)
    return int(r.json()["data"]["total"])

# S112 A2 工具化后新增 readCodeFile / writeCodeFile（经 ToolObjectContributor 扩展点登记，
# 由 AiToolRegistrySynchronizer 自动 upsert 入 ai_tool，零 Flyway），注册表由 6 → 8。
# S114 回归批同步断言（原 6 为 S99 时代口径，S112 起已过期）。
EXPECT_TOOLS = {
    "queryMyPendingTaskCount": ("read", 0),
    "queryMyPendingTasks": ("read", 0),
    "queryMyFlowInstances": ("read", 0),
    "queryMyMessages": ("read", 0),
    "urgeFlowInstance": ("write", 1),
    "sendInboxMessage": ("write", 1),
    "readCodeFile": ("read", 0),
    "writeCodeFile": ("write", 1),
}
EXPECT_TOOL_COUNT = len(EXPECT_TOOLS)

# ── Step 1: 登录 ──────────────────────────────────────────────────────────
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
body = r.json()
assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
HDR = {"Authorization": body["data"]["token"]}
log("PASS1", "admin 登录成功")

# ── Step 2: 注册表同步 8 工具 + 元数据正确 ────────────────────────────────
r = requests.get(f"{BASE}/ai/tool/page", params={"pageNum": 1, "pageSize": 20}, headers=HDR, timeout=15)
page = r.json()
assert page.get("code") == 0, f"注册表分页失败: {page}"
tools = {t["toolName"]: t for t in page["data"]["list"]}
assert int(page["data"]["total"]) == EXPECT_TOOL_COUNT, f"注册表工具数异常: {page['data']['total']}"
for name, (ttype, confirm) in EXPECT_TOOLS.items():
    assert name in tools, f"工具 {name} 未同步入注册表: {list(tools)}"
    t = tools[name]
    assert t["toolType"] == ttype, f"{name} 类型异常: {t['toolType']} != {ttype}"
    assert t["confirmRequired"] == confirm, f"{name} confirm_required 异常: {t['confirmRequired']}"
    assert t["status"] == 0, f"{name} 状态异常: {t['status']}"
log("PASS2", f"注册表同步 {EXPECT_TOOL_COUNT} 工具，类型/二次确认元数据全部正确")

# ── Step 3: REST 直连只读工具 + 业务基线对账 ─────────────────────────────
r = requests.get(f"{BASE}/workflow/task/pending/page",
                 params={"pageNum": 1, "pageSize": 1}, headers=HDR, timeout=15)
pending_baseline = int(r.json()["data"]["total"])
text = rest_call("queryMyPendingTasks", {"pageNum": 1, "pageSize": 5, "confirm": False})
data = json.loads(text)
assert data["total"] == pending_baseline, \
    f"查待办与 REST 基线不一致: 工具={data['total']} 基线={pending_baseline}"
log("PASS3a", f"queryMyPendingTasks OK：total={data['total']}（与管理端待办基线一致），首条={json.dumps(data['list'][0] if data['list'] else {}, ensure_ascii=False)[:150]}")

r = requests.get(f"{BASE}/workflow/instance/page",
                 params={"pageNum": 1, "pageSize": 1}, headers=HDR, timeout=15)
ins_baseline = int(r.json()["data"]["total"])
text = rest_call("queryMyFlowInstances", {"pageNum": 1, "pageSize": 5, "confirm": False})
data = json.loads(text)
assert data["total"] == ins_baseline, \
    f"查实例与 REST 基线不一致: 工具={data['total']} 基线={ins_baseline}"
log("PASS3b", f"queryMyFlowInstances OK：total={data['total']}（与我发起实例基线一致）")

r = requests.get(f"{BASE}/message/user/page", params={"pageNum": 1, "pageSize": 1}, headers=HDR, timeout=15)
if r.json().get("code") == 0:
    msg_baseline = int(r.json()["data"]["total"])
else:
    msg_baseline = None
text = rest_call("queryMyMessages", {"pageNum": 1, "pageSize": 5, "confirm": False})
data = json.loads(text)
if msg_baseline is not None:
    assert data["total"] == msg_baseline, \
        f"查通知与 REST 基线不一致: 工具={data['total']} 基线={msg_baseline}"
log("PASS3c", f"queryMyMessages OK：total={data['total']} unread={data.get('unread')}（基线={msg_baseline}）")

# ── Step 4: MCP 双暴露一致性 ─────────────────────────────────────────────
s = McpSession(HDR)
s.call("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                      "clientInfo": {"name": "s99-dual-probe", "version": "1.0"}})
s.post({"jsonrpc": "2.0", "method": "notifications/initialized"})
tools_list = s.call("tools/list")
mcp_names = {t["name"] for t in tools_list.get("result", {}).get("tools", [])}
assert mcp_names == set(EXPECT_TOOLS), f"MCP tools/list 集合与注册表不一致: {mcp_names}"
text = s.tool_call_text("queryMyPendingTasks", {"pageNum": 1, "pageSize": 5, "confirm": False})
mcp_data = json.loads(text)
assert mcp_data["total"] == pending_baseline, \
    f"MCP 查待办与 REST 基线不一致: {mcp_data['total']} != {pending_baseline}"
log("PASS4", f"MCP 双暴露一致：tools/list 6/6 全命中，queryMyPendingTasks MCP={mcp_data['total']} == REST 基线")

# ── Step 5: 发站内通知二次确认协议 + 副作用实证 ──────────────────────────
unread_before = json.loads(rest_call("queryMyMessages", {"pageNum": 1, "pageSize": 1, "confirm": False}))["unread"]
before_confirm = audit_total("sendInboxMessage", "need_confirm")
text = rest_call("sendInboxMessage", {"title": "S99预检", "content": "不应发出", "receiverUserIds": "1", "confirm": False})
assert "预检" in text, f"confirm=false 未被预检拦截: {text[:200]}"
assert audit_total("sendInboxMessage", "need_confirm") == before_confirm + 1, "预检未落 need_confirm 审计"
log("PASS5a", f"sendInboxMessage 预检拦截 OK：{text[:100]}")

text = rest_call("sendInboxMessage", {"title": "S99工具通知", "content": "A2 首批业务工具发站内通知实证", "receiverUserIds": "1", "confirm": True})
assert "发送成功" in text, f"confirm=true 未放行执行: {text[:200]}"
after = json.loads(rest_call("queryMyMessages", {"pageNum": 1, "pageSize": 5, "confirm": False}))
assert after["unread"] == unread_before + 1, f"副作用未落地：未读 {unread_before} -> {after['unread']}"
hit = next((m for m in after["list"] if m.get("title") == "S99工具通知"), None)
assert hit, f"新消息未出现在收件列表: {[m.get('title') for m in after['list']]}"
log("PASS5b", f"sendInboxMessage 确认放行 OK：消息 ID 落地，未读 {unread_before} -> {after['unread']}")

# ── Step 6: 催办二次确认 + 真实催办（新实例规避限频） ────────────────────
r = requests.get(f"{BASE}/workflow/definition/page",
                 params={"pageNum": 1, "pageSize": 50}, headers=HDR, timeout=15)
defs = r.json()["data"]["list"]
target = next((d for d in defs if "请假" in (d.get("flowName") or "")), None)
assert target, f"未找到请假流程定义: {[d.get('flowName') for d in defs]}"
r = requests.post(f"{BASE}/workflow/instance/start",
                  json={"flowCode": target["flowCode"], "businessName": "S99催办实证",
                        "variable": {"days": 1}}, headers=HDR, timeout=30)
body = r.json()
assert body.get("code") == 0, f"发起实例失败: {json.dumps(body, ensure_ascii=False)[:300]}"
ins_id = body["data"]["id"]
text = rest_call("urgeFlowInstance", {"instanceId": ins_id, "confirm": False})
assert "预检" in text, f"催办 confirm=false 未被预检拦截: {text[:200]}"
log("PASS6a", f"urgeFlowInstance 预检拦截 OK（实例 {ins_id}）")

text = rest_call("urgeFlowInstance", {"instanceId": ins_id, "confirm": True})
assert "催办成功" in text, f"催办 confirm=true 未放行: {text[:200]}"
log("PASS6b", f"urgeFlowInstance 确认放行 OK：{text[:100]}")
requests.put(f"{BASE}/workflow/instance/{ins_id}/terminate", headers=HDR, timeout=10)
log("CLEAN", f"催办实证实例 {ins_id} 已终止清理")

# ── Step 7: 审计落库 + 未注册工具拒绝 ────────────────────────────────────
assert audit_total("urgeFlowInstance") >= 2, "催办审计不足（预检+成功）"
assert audit_total("sendInboxMessage") >= 2, "发通知审计不足（预检+成功）"
log("PASS7a", f"审计落库 OK：urge={audit_total('urgeFlowInstance')} 条，send={audit_total('sendInboxMessage')} 条")

r = requests.post(f"{BASE}/ai/tool/call", json={"toolName": "noSuchTool", "args": {}},
                  headers=HDR, timeout=15)
body = r.json()
assert body.get("code") != 0, "未注册工具未被拒绝"
log("PASS7b", f"未注册工具 REST 直连被拒：code={body.get('code')} msg={body.get('msg')}")

log("ALL-PASS", "S99 主 E2E 全链路通过：注册同步 8 工具/REST 三基线对账/MCP 双暴露一致/双写操作二次确认+副作用实证/审计/未注册拒绝")
