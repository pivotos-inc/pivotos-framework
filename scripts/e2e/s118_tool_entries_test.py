#!/usr/bin/env python3
"""
PivotOS S118 E2E：AI-2 @Tool **四入口**齐全 + migration 详情端点语义修复

四入口（同一 @Tool 注册表、同一 AiToolService.invokeTool 守卫链路）：
  ① REST  POST /ai/tool/call
  ② MCP   /sse + tools/list + tools/call
  ③ SSE   POST /ai/tool/call/stream（本 Sprint 新增，帧：meta → result|error → done）
  ④ CLI   scripts/pivot-ai-tool list/call（本 Sprint 新增，零第三方依赖，内部可走 --stream）

搭车断言：migration 详情端点「不存在」与「空详情」不再同返 code=0。

口径：
  - 全程只读工具，不落写操作（写工具非幂等，S117 实测），无需数据库自清；
  - SSE  stream 手工分帧透视 UTF-8 多字节被 chunk 切断的问题（S97 踩坑：不能用 iter_lines+decode_unicode）；
  - 四入口取到同一个工具的结果必须**同值**，这是「同源守卫」的可观察证据。
"""
import json
import queue
import subprocess
import sys
import threading
import time

import requests

BASE = "http://localhost:8080"
CLI = "/Users/huweilong/Documents/File/Project/PivotOS Technology/PivotOS/pivotos-framework/scripts/pivot-ai-tool"
TOOL = "queryMyPendingTaskCount"
ARGS = {"confirm": True}

frames = queue.Queue()
endpoint_holder = {}
HDR = {}


def log(tag, msg):
    print(f"[{tag}]", msg, flush=True)


# ── Step 1: 登录 ──────────────────────────────────────────
log("STEP1", "登录获取 token ...")
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
body = r.json()
assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
token = body["data"]["token"]
HDR = {"Authorization": token}
log("PASS1", "token OK")


# ── Step 2: 入口① REST ─────────────────────────────────────
log("STEP2", "REST 入口 POST /ai/tool/call ...")
r = requests.post(f"{BASE}/ai/tool/call", json={"toolName": TOOL, "args": ARGS},
                  headers=HDR, timeout=60)
body = r.json()
assert body.get("code") == 0, f"REST 调用失败: {json.dumps(body, ensure_ascii=False)[:300]}"
rest_result = str(body["data"]).strip()
assert rest_result.isdigit(), f"REST 返回值非数字：{rest_result}"
log("PASS2", f"REST → {rest_result}")


# ── Step 3: 入口② MCP ─────────────────────────────────────
def sse_reader():
    with requests.get(f"{BASE}/sse", stream=True, timeout=(10, 300), headers=HDR) as resp:
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
                buf = buf[idx + sep if crlf < 0 or idx < crlf else crlf + sep:]
                cur_event, cur_data = None, []
                for line in raw.decode("utf-8", errors="replace").split("\n"):
                    line = line.rstrip("\r")
                    if line.startswith("event:"):
                        cur_event = line[6:].strip()
                    elif line.startswith("data:"):
                        cur_data.append(line[5:].strip())
                if cur_event or cur_data:
                    data = "\n".join(cur_data)
                    if cur_event == "endpoint":
                        endpoint_holder["url"] = data
                        log("SSE", f"MCP endpoint={data}")
                    frames.put((cur_event, data))


def wait_endpoint(timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if "url" in endpoint_holder:
            return endpoint_holder["url"]
        time.sleep(0.2)
    raise AssertionError("SSE 未在超时内下发 endpoint 事件")


def wait_response(msg_id, timeout=30):
    deadline, stash = time.time() + timeout, []
    try:
        while time.time() < deadline:
            try:
                _, data = frames.get(timeout=1)
            except queue.Empty:
                continue
            if not data:
                continue
            try:
                obj = json.loads(data)
            except json.JSONDecodeError:
                continue
            if obj.get("id") == msg_id:
                return obj
            stash.append((_, data))
        raise AssertionError(f"超时未收到 id={msg_id} 的响应帧")
    finally:
        for item in reversed(stash):
            frames.queue.appendleft(item)


log("STEP3", "MCP 入口 /sse + tools/list + tools/call ...")
threading.Thread(target=sse_reader, daemon=True).start()
endpoint = wait_endpoint()
msg_url = BASE + (endpoint if endpoint.startswith("/") else "/" + endpoint)


def post(payload, label):
    resp = requests.post(msg_url, json=payload, headers=HDR, timeout=30)
    assert resp.status_code in (200, 202), f"{label} POST 失败 HTTP {resp.status_code}: {resp.text[:200]}"


post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
    "protocolVersion": "2024-11-05", "capabilities": {},
    "clientInfo": {"name": "s118-probe", "version": "1.0"}}}, "initialize")
resp = wait_response(1)
assert resp["result"].get("serverInfo"), f"initialize 失败: {resp}"
post({"jsonrpc": "2.0", "method": "notifications/initialized"}, "initialized")

post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, "tools/list")
tools = wait_response(2)["result"].get("tools", [])
names = [t["name"] for t in tools]
assert TOOL in names, f"MCP 未列出目标工具：{names}"

post({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
      "params": {"name": TOOL, "arguments": ARGS}}, "tools/call")
result = wait_response(3)["result"]
assert not result.get("isError"), f"tools/call 返回错误：{result}"
mcp_result = result.get("content", [{}])[0].get("text", "").strip()
assert mcp_result == rest_result, f"MCP 与 REST 结果不一致：{mcp_result} vs {rest_result}"
log("PASS3", f"MCP → {mcp_result}（tools/list 共 {len(tools)} 个，与 REST 同值）")


# ── Step 4: 入口③ SSE ─────────────────────────────────────
def read_sse(payload):
    """消费 SSE 入口：手工分帧（item: event/data），返回 [(event, data)]"""
    with requests.post(f"{BASE}/ai/tool/call/stream", json=payload, headers=HDR,
                       stream=True, timeout=(10, 120)) as resp:
        assert resp.status_code == 200, f"SSE 调用失败 HTTP {resp.status_code}: {resp.text[:200]}"
        out, cur_event, cur_data = [], None, []
        buf = ""
        for chunk in resp.iter_content(chunk_size=1024):
            buf += chunk.decode("utf-8", errors="replace")
            while "\n\n" in buf:
                raw, buf = buf.split("\n\n", 1)
                cur_event, cur_data = None, []
                for line in raw.replace("\r\n", "\n").split("\n"):
                    if line.startswith("event:"):
                        cur_event = line[6:].strip()
                    elif line.startswith("data:"):
                        cur_data.append(line[5:].strip())
                if cur_event or cur_data:
                    out.append((cur_event, "\n".join(cur_data)))
        return out


log("STEP4", "SSE 入口 POST /ai/tool/call/stream ...")
stream_frames = read_sse({"toolName": TOOL, "args": ARGS})
log("SSE-FRAMES", f"{stream_frames}")
events = [e for e, _ in stream_frames]
assert events == ["meta", "result", "done"], f"SSE 帧序不符合约定：{events}"
sse_result = dict(stream_frames)["result"]
assert sse_result == rest_result, f"SSE 与 REST 结果不一致：{sse_result} vs {rest_result}"
log("PASS4", f"SSE → {sse_result}（帧序 {' → '.join(events)}，与 REST/MCP 同值）")

log("STEP5", "SSE 入口错误路径：未注册工具应落成 error 帧而非 HTTP 5xx ...")
err_frames = read_sse({"toolName": "notExistToolS118", "args": {}})
err_events = [e for e, _ in err_frames]
assert err_events == ["meta", "error", "done"], f"SSE 错误帧序不符合约定：{err_events}"
err_payload = json.loads(dict(err_frames)["error"])
assert err_payload["code"] == 5060, f"错误码不符：{err_payload}"
log("PASS5", f"SSE 错误帧 code={err_payload['code']} msg={err_payload['msg']}（仍以 done 收尾）")


# ── Step 6: 入口④ CLI ─────────────────────────────────────
log("STEP6", "CLI 入口 scripts/pivot-ai-tool ...")
env_out = subprocess.run([sys.executable, CLI, "list", "--size", "5", "--json"],
                         capture_output=True, timeout=120)
assert env_out.returncode == 0, f"CLI list 失败 rc={env_out.returncode}: {env_out.stderr.decode()[:300]}"
listed = json.loads(env_out.stdout.decode("utf-8"))
assert TOOL in [t["toolName"] for t in listed], f"CLI list 未列出目标工具：{[t['toolName'] for t in listed]}"

call_out = subprocess.run([sys.executable, CLI, "call", "--tool", TOOL,
                           "--args", json.dumps(ARGS), "--json"],
                          capture_output=True, timeout=120)
assert call_out.returncode == 0, f"CLI call 失败 rc={call_out.returncode}: {call_out.stderr.decode()[:300]}"
cli_result = json.loads(call_out.stdout.decode("utf-8"))["result"]
assert str(cli_result) == rest_result, f"CLI 与 REST 结果不一致：{cli_result} vs {rest_result}"

stream_out = subprocess.run([sys.executable, CLI, "call", "--tool", TOOL,
                             "--args", json.dumps(ARGS), "--stream"],
                            capture_output=True, timeout=120)
stream_text = stream_out.stdout.decode("utf-8")
assert stream_out.returncode == 0, f"CLI --stream 失败 rc={stream_out.returncode}: {stream_text[:300]}"
assert "event=done" in stream_text, f"CLI --stream 未收到 done 帧：{stream_text[:300]}"
log("PASS6", f"CLI list/call/call --stream 三用退出码 0，call → {cli_result}（与 REST 同值）")


# ── Step 7: 搭车断言 migration 详情端点语义 ──────────────────
log("STEP7", "migration 详情端点「不存在」不再同返 code=0 ...")
BAD = "999999999999999999"
r = requests.get(f"{BASE}/migration/task/{BAD}", headers=HDR, timeout=30)
body = r.json()
assert body.get("code") == 8000, f"迁移任务不存在应返回 8000，实际：{json.dumps(body, ensure_ascii=False)[:200]}"
assert body.get("success") is False, f"success 应为 false：{body}"
r = requests.get(f"{BASE}/migration/artifact/{BAD}", headers=HDR, timeout=30)
body = r.json()
assert body.get("code") == 8064, f"迁移产物不存在应返回 8064，实际：{json.dumps(body, ensure_ascii=False)[:200]}"

r = requests.get(f"{BASE}/migration/task/page", headers=HDR, params={"pageNum": 1, "pageSize": 1}, timeout=30)
rows = r.json()["data"]["list"]
assert rows, "migration_task 列表为空，无法取真实 ID 做正向断言"
real_id = rows[0]["id"]
r = requests.get(f"{BASE}/migration/task/{real_id}", headers=HDR, timeout=30)
body = r.json()
assert body.get("code") == 0 and body.get("data"), f"真实任务详情应 code=0 且带 data：{body}"
log("PASS7", f"不存在任务→8000 / 不存在产物→8064 / 真实任务 {real_id}→code=0（语义已分离）")

log("ALL-PASS", f"S118 四入口 + migration 语义修复验证通过："
                f"REST/MCP/SSE/CLI 四入口同为 {rest_result}；详情端点不存在返回 8000/8064")
