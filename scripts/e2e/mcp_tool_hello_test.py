#!/usr/bin/env python3
"""
PivotOS S97 A2 前置验证 E2E 脚本（2026-08-23 第 5 轮）
目标（13 号清单 S97 一票否决点）：
  ① spring-ai-starter-mcp-server-webmvc 2.0.0 与 Boot 4.1/JDK 25 兼容（启动即证）；
  ② @Tool 方法 queryMyPendingTaskCount 经 MCP SSE 端点被列出（tools/list）；
  ③ 经 tools/call 被成功调用并返回结果。
姿势：GET /sse 建立 SSE 连接取 endpoint 事件 → POST /mcp/message?sessionId=xxx
依次发 initialize / notifications/initialized / tools/list / tools/call，
响应帧从 SSE 流采集（后台线程收帧入队列）。
"""
import requests, json, sys, threading, queue, time, re

BASE = "http://localhost:8080"
frames = queue.Queue()
endpoint_holder = {}

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

def sse_reader():
    """SSE 流读取线程：字节累积手工分帧（不用 iter_lines+decode_unicode：UTF-8 多字节
    字符被 chunk 边界切断会产生替换符，中文 description 帧的 JSON 解析会失败——S97 实测坑）"""
    with requests.get(f"{BASE}/sse", stream=True, timeout=(10, 300)) as r:
        assert r.status_code == 200, f"SSE 连接失败 HTTP {r.status_code}"
        buf = b""
        for chunk in r.iter_content(chunk_size=1024):
            if not chunk:
                continue
            buf += chunk
            while True:
                # SSE 帧以空行（\n\n 或 \r\n\r\n）分隔
                idx = buf.find(b"\n\n")
                crlf = buf.find(b"\r\n\r\n")
                if idx < 0 and crlf < 0:
                    break
                if crlf >= 0 and (idx < 0 or crlf < idx):
                    raw_frame, sep = buf[:crlf], 4
                else:
                    raw_frame, sep = buf[:idx], 2
                buf = buf[idx + sep if crlf < 0 or idx < crlf else crlf + sep:]
                cur_event, cur_data = None, []
                for line in raw_frame.decode("utf-8", errors="replace").split("\n"):
                    line = line.rstrip("\r")
                    if line.startswith("event:"):
                        cur_event = line[6:].strip()
                    elif line.startswith("data:"):
                        cur_data.append(line[5:].strip())
                if cur_event or cur_data:
                    data = "\n".join(cur_data)
                    log("FRAME", f"event={cur_event} len={len(data)} data={data[:120]}")
                    if cur_event == "endpoint":
                        endpoint_holder["url"] = data
                        log("SSE", f"endpoint={data}")
                    frames.put((cur_event, data))

def wait_endpoint(timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if "url" in endpoint_holder:
            return endpoint_holder["url"]
        time.sleep(0.2)
    raise AssertionError("SSE 未在超时内下发 endpoint 事件")

def wait_response(msg_id, timeout=30):
    """从 SSE 帧队列中取指定 id 的 JSON-RPC 响应；非匹配帧放回队列头部不丢弃"""
    deadline = time.time() + timeout
    stash = []
    try:
        while time.time() < deadline:
            try:
                event, data = frames.get(timeout=1)
            except queue.Empty:
                continue
            if not data:
                continue
            try:
                obj = json.loads(data)
            except json.JSONDecodeError as e:
                log("SKIP", f"JSON 解析失败 event={event} err={e.msg} pos={e.pos} len={len(data)}")
                continue
            if obj.get("id") == msg_id:
                return obj
            log("STASH", f"暂存非目标帧 id={obj.get('id')} method={obj.get('method')}")
            stash.append((event, data))
        raise AssertionError(f"超时未收到 id={msg_id} 的响应帧")
    finally:
        # 非匹配帧回填（倒序 insert 保持原顺序，queue 无 push 头，用内部 deque 重建）
        for item in reversed(stash):
            frames.queue.appendleft(item)

# ── Step 1: SSE 连接 + endpoint ───────────────────────────
log("STEP1", "建立 SSE 连接 ...")
t = threading.Thread(target=sse_reader, daemon=True)
t.start()
endpoint = wait_endpoint()
msg_url = BASE + (endpoint if endpoint.startswith("/") else "/" + endpoint)
log("PASS1", f"SSE endpoint 就绪：{msg_url}")

def post(payload, label):
    r = requests.post(msg_url, json=payload, timeout=30)
    assert r.status_code in (200, 202), f"{label} POST 失败 HTTP {r.status_code}: {r.text[:200]}"
    log("POST", f"{label} → HTTP {r.status_code}（accepted）")

# ── Step 2: initialize 握手 ───────────────────────────────
post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
    "protocolVersion": "2024-11-05", "capabilities": {},
    "clientInfo": {"name": "s97-probe", "version": "1.0"}}}, "initialize")
resp = wait_response(1)
server_info = resp["result"].get("serverInfo", {})
log("PASS2", f"initialize 握手成功：server={server_info.get('name')} "
            f"version={server_info.get('version')} "
            f"capabilities={list(resp['result'].get('capabilities', {}).keys())}")
post({"jsonrpc": "2.0", "method": "notifications/initialized"}, "initialized")

# ── Step 3: tools/list —— @Tool 被列出（一票否决点②） ────
post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, "tools/list")
resp = wait_response(2)
tools = resp["result"].get("tools", [])
names = [tool["name"] for tool in tools]
log("STEP3", f"tools/list 返回 {len(tools)} 个工具：{names}")
assert "queryMyPendingTaskCount" in names, f"未列出目标工具：{names}"
target = next(tool for tool in tools if tool["name"] == "queryMyPendingTaskCount")
log("PASS3", f"queryMyPendingTaskCount 已列出：description={target.get('description', '')[:60]}...")

# ── Step 4: tools/call —— 工具被调用（一票否决点③） ──────
post({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
      "params": {"name": "queryMyPendingTaskCount", "arguments": {}}}, "tools/call")
resp = wait_response(3)
result = resp["result"]
assert not result.get("isError"), f"tools/call 返回错误：{result}"
content = result.get("content", [])
text = content[0].get("text") if content else None
log("PASS4", f"tools/call 成功：content={json.dumps(content, ensure_ascii=False)[:200]}")
assert text is not None and text.strip().isdigit(), f"返回值非数字：{text}"
log("ALL-PASS", f"MCP SSE 全链路验证通过：@Tool 注册→列出→调用，返回待办数={text}（SSE 无鉴权会话按未登录口径为 0 属预期）")
