#!/usr/bin/env python3
"""
PivotOS S98 二次确认协议专项 E2E（2026-08-23 第 4 轮）
前置：ai_tool_registry_test.py 已 ALL-PASS（基础链路成立）。
手法：SQL 把 queryMyPendingTaskCount 的 confirm_required 置 1（模拟写操作工具），
经 REST status 端点触发守卫缓存失效，验证：
  ① confirm=false 调用 → 返回【预检】文本且不执行（need_confirm 审计 +1）；
  ② confirm=true 调用 → 放行执行返回待办数（success 审计 +1）；
  ③ 恢复 confirm_required=0 → 无 confirm 亦放行（只读语义复原）。
"""
import requests, json, subprocess, threading, queue, time

BASE = "http://localhost:8080"
MYSQL_CP = r"C:\Users\huweilong\.m2\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
TOGGLER = r"E:\Develop\Project\PivotOS Inc\PivotOS\pivotos-tmp\S98ConfirmToggle.java"

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

def toggle(flag):
    r = subprocess.run(["java", "-cp", MYSQL_CP, TOGGLER, str(flag)],
                       capture_output=True, text=True, timeout=60)
    out = (r.stdout or "") + (r.stderr or "")
    assert r.returncode == 0 and f"confirm_required={flag} affected=1" in out, f"DB 切换失败：{out}"
    log("DB", out.strip())

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

# ── Step 1: 登录 + 定位工具 ────────────────────────────────────────────────
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
body = r.json()
assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
HDR = {"Authorization": body["data"]["token"]}
r = requests.get(f"{BASE}/ai/tool/page", params={"pageNum": 1, "pageSize": 20}, headers=HDR, timeout=15)
target = next(t for t in r.json()["data"]["list"] if t["toolName"] == "queryMyPendingTaskCount")
tool_id = target["id"]

def audit_count(status):
    r = requests.get(f"{BASE}/ai/tool/invoke/page",
                     params={"pageNum": 1, "pageSize": 100, "toolName": "queryMyPendingTaskCount",
                             "invokeStatus": status}, headers=HDR, timeout=15)
    return int(r.json()["data"]["total"])

def refresh_guard_cache():
    # status 端点更新后失效守卫缓存（此处 status 值不变，仅借道刷缓存）
    r = requests.put(f"{BASE}/ai/tool/{tool_id}/status", params={"status": 0}, headers=HDR, timeout=15)
    assert r.json().get("code") == 0, f"缓存刷新失败: {r.text[:200]}"

s = McpSession(HDR)
s.call("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                      "clientInfo": {"name": "s98-confirm-probe", "version": "1.0"}})
s.post({"jsonrpc": "2.0", "method": "notifications/initialized"})
log("STEP1", "登录 + MCP 握手就绪")

try:
    # ── Step 2: 开启预检 → 无 confirm 被拦 ────────────────────────────────
    toggle(1)
    refresh_guard_cache()
    need_before = audit_count("need_confirm")
    text = s.tool_call_text("queryMyPendingTaskCount", {"confirm": False})
    assert "预检" in text, f"二次确认闸未生效：{text[:200]}"
    assert audit_count("need_confirm") == need_before + 1, "预检拦截未落 need_confirm 审计"
    log("PASS2", f"confirm=false 调用被预检拦截 + 审计落库：{text[:100]}")

    # ── Step 3: confirm=true 放行 ─────────────────────────────────────────
    success_before = audit_count("success")
    text = s.tool_call_text("queryMyPendingTaskCount", {"confirm": True})
    assert text.strip().isdigit(), f"confirm=true 未放行：{text[:200]}"
    assert audit_count("success") == success_before + 1, "确认执行未落 success 审计"
    log("PASS3", f"confirm=true 放行执行：待办数={text}")
finally:
    # ── Step 4: 恢复只读语义（无论成败必须还原）───────────────────────────
    toggle(0)
    refresh_guard_cache()
    text = s.tool_call_text("queryMyPendingTaskCount", {"confirm": False})
    assert text.strip().isdigit(), f"恢复后调用失败：{text[:200]}"
    log("PASS4", f"confirm_required 恢复 0，confirm=false 放行：待办数={text}")

log("ALL-PASS", "S98 二次确认协议验证通过：预检拦截 + confirm 放行 + 审计双态 + 现场还原")
