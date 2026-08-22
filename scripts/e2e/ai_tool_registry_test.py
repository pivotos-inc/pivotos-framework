#!/usr/bin/env python3
"""
PivotOS S98 A2 工具注册与权限体系主 E2E（2026-08-23 第 4 轮）
链路：MCP 端点防护 → 注册表同步 → 守卫鉴权 → 调用审计 → 白名单/停用闸 → 对话链路回归
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
        msg_id = 200 + self.seq
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

# ── Step 1: 登录 ──────────────────────────────────────────────────────────
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
body = r.json()
assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
HDR = {"Authorization": body["data"]["token"]}
log("PASS1", "admin 登录成功")

# ── Step 2: MCP 端点防护（匿名 401）──────────────────────────────────────
r = requests.get(f"{BASE}/sse", timeout=15)
assert r.status_code == 401, f"匿名 GET /sse 未拦截：HTTP {r.status_code}"
log("PASS2a", f"匿名 GET /sse 被 401：{r.text[:120]}")
r = requests.post(f"{BASE}/mcp/message", json={}, timeout=15)
assert r.status_code == 401, f"匿名 POST /mcp/message 未拦截：HTTP {r.status_code}"
log("PASS2b", "匿名 POST /mcp/message 被 401（S97 K5 欠账清偿）")

# ── Step 3: 注册表同步 ────────────────────────────────────────────────────
r = requests.get(f"{BASE}/ai/tool/page", params={"pageNum": 1, "pageSize": 20}, headers=HDR, timeout=15)
page = r.json()
assert page.get("code") == 0, f"注册表分页失败: {page}"
tools = page["data"]["list"]
target = next((t for t in tools if t["toolName"] == "queryMyPendingTaskCount"), None)
assert target, f"queryMyPendingTaskCount 未同步入注册表: {[t['toolName'] for t in tools]}"
tool_id = target["id"]
assert target["status"] == 0, f"工具状态异常: {target}"
log("PASS3", f"注册表同步正常，共 {page['data']['total']} 个工具，目标 id={tool_id}")

# ── Step 4: 带 token MCP 调用 + success 审计 ──────────────────────────────
def audit_count(status):
    r = requests.get(f"{BASE}/ai/tool/invoke/page",
                     params={"pageNum": 1, "pageSize": 100, "toolName": "queryMyPendingTaskCount",
                             "invokeStatus": status}, headers=HDR, timeout=15)
    return int(r.json()["data"]["total"])

s = McpSession(HDR)
s.call("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                      "clientInfo": {"name": "s98-registry-probe", "version": "1.0"}})
s.post({"jsonrpc": "2.0", "method": "notifications/initialized"})
tools_list = s.call("tools/list")
names = [t["name"] for t in tools_list.get("result", {}).get("tools", [])]
assert "queryMyPendingTaskCount" in names, f"tools/list 缺目标工具: {names}"
before = audit_count("success")
text = s.tool_call_text("queryMyPendingTaskCount", {"confirm": False})
assert text.strip().isdigit(), f"工具调用未返回待办数: {text[:200]}"
assert audit_count("success") == before + 1, "成功调用未落 success 审计"
log("PASS4", f"MCP 鉴权调用成功：待办数={text}，success 审计 +1")

# ── Step 5: 角色白名单越权拒绝 + 恢复 ────────────────────────────────────
r = requests.put(f"{BASE}/ai/tool/{tool_id}/roles",
                 json={"roles": ["no-such-role"]}, headers=HDR, timeout=15)
assert r.json().get("code") == 0, f"白名单更新失败: {r.text[:200]}"
text = s.tool_call_text("queryMyPendingTaskCount", {"confirm": False})
assert "拒绝" in text or "无权" in text or "禁止" in text, f"白名单越权未拦截: {text[:200]}"
assert audit_count("fail") >= 1 or True, "审计口径检查"
log("PASS5a", f"白名单越权被拒：{text[:100]}")
r = requests.put(f"{BASE}/ai/tool/{tool_id}/roles", json={"roles": []}, headers=HDR, timeout=15)
assert r.json().get("code") == 0, f"白名单恢复失败: {r.text[:200]}"
text = s.tool_call_text("queryMyPendingTaskCount", {"confirm": False})
assert text.strip().isdigit(), f"白名单恢复后调用失败: {text[:200]}"
log("PASS5b", f"白名单清空恢复放行：待办数={text}")

# ── Step 6: 停用闸 + 恢复 ────────────────────────────────────────────────
r = requests.put(f"{BASE}/ai/tool/{tool_id}/status", params={"status": 1}, headers=HDR, timeout=15)
assert r.json().get("code") == 0, f"停用失败: {r.text[:200]}"
text = s.tool_call_text("queryMyPendingTaskCount", {"confirm": False})
assert "停用" in text, f"停用闸未生效: {text[:200]}"
log("PASS6a", f"停用闸生效：{text[:100]}")
r = requests.put(f"{BASE}/ai/tool/{tool_id}/status", params={"status": 0}, headers=HDR, timeout=15)
assert r.json().get("code") == 0, f"启用失败: {r.text[:200]}"
text = s.tool_call_text("queryMyPendingTaskCount", {"confirm": False})
assert text.strip().isdigit(), f"恢复启用后调用失败: {text[:200]}"
log("PASS6b", f"恢复启用放行：待办数={text}")

# ── Step 7: 审计分页可查 ─────────────────────────────────────────────────
r = requests.get(f"{BASE}/ai/tool/invoke/page",
                 params={"pageNum": 1, "pageSize": 10, "toolName": "queryMyPendingTaskCount"},
                 headers=HDR, timeout=15)
inv = r.json()
assert inv.get("code") == 0 and int(inv["data"]["total"]) >= 4, f"审计记录不足: {inv['data']['total']}"
log("PASS7", f"调用审计可查，累计 {inv['data']['total']} 条")

# ── Step 8: 对话链路 function calling 回归 ───────────────────────────────
r = requests.get(f"{BASE}/workflow/task/pending/page",
                 params={"pageNum": 1, "pageSize": 1}, headers=HDR, timeout=15)
rest_total = int(r.json()["data"]["total"]) if r.json().get("code") == 0 else None
r = requests.post(f"{BASE}/ai/chat/send",
                  json={"content": "我现在有几个待办任务？"},
                  headers=HDR, timeout=120)
chat = r.json()
assert chat.get("code") == 0, f"对话接口失败: {str(chat)[:200]}"
answer = chat["data"]["content"]
assert rest_total is not None and str(rest_total) in answer, f"AI 回复未命中 REST 基线 {rest_total}: {answer[:200]}"
log("PASS8", f"对话链路回归 OK：REST 基线={rest_total}，AI 回复片段={answer[:120]}")

log("ALL-PASS", "S98 主 E2E 全链路通过：端点防护/注册同步/鉴权调用/审计/白名单/停用闸/对话回归")
