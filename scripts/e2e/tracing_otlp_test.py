#!/usr/bin/env python3
"""v3.2.0 · P1-B8-2 链路追踪 E2E（自起自停 fat jar，只 kill 本轮 PID；内置 OTLP 接收器，零第三方依赖）。

要证明的只有一件事：**日志 / 响应头里的 X-Trace-Id 与 OTLP 导出的 span.traceId 是同一个值**。
做不到这一点，链路面就还是断的 —— 拿着日志里的 ID 去 Jaeger 搜，什么也搜不到。

四个场景：
  ┃ 场景 1（正向·入站带头发）：请求带 X-Trace-Id → 收到的 span.traceId 必须等于它
  ┃ 场景 2（正向·普通请求）：不带头（TraceIdFilter 会生成）→ 响应头 X-Trace-Id 必须等于
  ┃                          span.traceId（这条覆盖占绝大多数的普通请求，是桥接是否真闭环的判据）
  ┃ 场景 3（负向·总开关关）：PIVOTOS_TRACING_ENABLED 不设 → 请求照常 200，但**一个 span 都收不到**
  ┃ 场景 4（MDC 身份）：带 token 请求 → 应用日志里出现 [u=<userId>,t=<tenantId>]
  ┃                    （不是 u=-,t=-；未开 tracing 时恒为 u=-,t=-，属预期）

为什么自己写一个 OTLP 接收器而不是只装 Jaeger：
  ① Jaeger 需要 docker（本机不总是起得来），而 OTLP/HTTP 就是个 POST —— 一百行就能收；
  ② 断言「traceId 相等」只需解析 protobuf 里两个字段，不必引入 protobuf 依赖；
  ③ 有了它，本脚本在**任何能起服的机器**上都能跑，Jaeger 只是加分项（--jaeger-url 可选）。

用法：
    python3 scripts/e2e/tracing_otlp_test.py                       # 全跑
    python3 scripts/e2e/tracing_otlp_test.py --only=bridge         # 只跑场景 1
    python3 scripts/e2e/tracing_otlp_test.py --only=noheader       # 只跑场景 2
    python3 scripts/e2e/tracing_otlp_test.py --only=disabled       # 只跑场景 3（负向）
    python3 scripts/e2e/tracing_otlp_test.py --only=mdc            # 只跑场景 4
    python3 scripts/e2e/tracing_otlp_test.py --jaeger-url http://127.0.0.1:16686
"""
import argparse
import json
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = "http://127.0.0.1:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAR = os.path.join(ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
WORK_ROOT = "/tmp/pivotos-v32a-tracing-e2e"

OTLP_PORT = 4318
OTLP_ENDPOINT = f"http://127.0.0.1:{OTLP_PORT}/v1/traces"
TRACE_HEADER = "X-Trace-Id"

# --local-db 用的环境变量：把数据源/Redis 指到本机容器（与 CI services 同参数）。
# dev yml 的兜底连接串指向开发服务器；该机不可达时应用起不来（Flyway 校验卡住），
# 于是「收不到 span」就会被误判成桥接失败 —— 两件事必须能分开。
LOCAL_DB_ENV = {
    "PIVOTOS_MYSQL_URL": "jdbc:mysql://127.0.0.1:3306/pivotos_dev?useUnicode=true&characterEncoding=utf8"
                         "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
                         "&createDatabaseIfNotExist=true&allowMultiQueries=false",
    "PIVOTOS_MYSQL_USERNAME": "root",
    "PIVOTOS_MYSQL_PASSWORD": "root_ci",
    # ⚠️ 密码绝不能是空串：Redisson 拿到空串照样发 AUTH "" 会被 Redis 拒绝
    "PIVOTOS_REDIS_HOST": "127.0.0.1",
    "PIVOTOS_REDIS_PASSWORD": "root_ci",
}

LOCAL_DB = False
# Jaeger 真机验证（可选）：给了 --jaeger-url 才会跑 jaeger 场景
JAEGER_URL = None
JAEGER_OTLP_ENDPOINT = "http://127.0.0.1:4318/v1/traces"

results = []
received_spans = []


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(tag, cond, msg):
    results.append((tag, bool(cond)))
    if cond:
        log("PASS", f"{tag} {msg}")
    else:
        log("FAIL", f"{tag} {msg}")
        raise AssertionError(f"{tag} 断言失败：{msg}")


# ────────────────────── 最小 protobuf 解析（只够取 traceId / spanId / name） ──────────────────────

def read_varint(buf, i):
    shift = 0
    value = 0
    while True:
        byte = buf[i]
        i += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, i
        shift += 7


def iter_fields(buf):
    """产出 (field_number, wire_type, value)：wire_type=2 时 value 是 bytes，0 时是 int。"""
    i = 0
    while i < len(buf):
        key, i = read_varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, i = read_varint(buf, i)
            yield field, wire, value
        elif wire == 2:
            length, i = read_varint(buf, i)
            yield field, wire, buf[i:i + length]
            i += length
        elif wire == 5:
            yield field, wire, buf[i:i + 4]
            i += 4
        elif wire == 1:
            yield field, wire, buf[i:i + 8]
            i += 8
        else:
            return


def parse_otlp_traces(body):
    """TraceServiceRequest → [(trace_id_hex, span_id_hex, name)]。

    message TraceServiceRequest { repeated ResourceSpans resource_spans = 1; }
    message ResourceSpans      { Resource resource = 1; repeated ScopeSpans scope_spans = 2; }
    message ScopeSpans         { ... ; repeated Span spans = 2; }
    message Span               { bytes trace_id = 1; bytes span_id = 2; string name = 5; }
    """
    spans = []
    for f1, w1, v1 in iter_fields(body):
        if f1 != 1 or w1 != 2:
            continue  # resource_spans
        for f2, w2, v2 in iter_fields(v1):
            if f2 != 2 or w2 != 2:
                continue  # scope_spans
            for f3, w3, v3 in iter_fields(v2):
                if f3 != 2 or w3 != 2:
                    continue  # spans
                trace_id = span_id = name = ""
                for f4, w4, v4 in iter_fields(v3):
                    if f4 == 1 and w4 == 2:
                        trace_id = v4.hex()
                    elif f4 == 2 and w4 == 2:
                        span_id = v4.hex()
                    elif f4 == 5 and w4 == 2:
                        name = v4.decode("utf-8", "replace")
                spans.append((trace_id, span_id, name))
    return spans


# ────────────────────────── OTLP 接收器 ──────────────────────────

class OtlpHandler(BaseHTTPRequestHandler):

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        for span in parse_otlp_traces(body):
            received_spans.append(span)
            log("OTLP", f"收到 span traceId={span[0]} spanId={span[1]} name={span[2]}")
        # OTLP 只认 200 + 空 ExportTraceServiceResponse；返回别的会被 exporter 判为失败并重试
        self.send_response(200)
        self.send_header("Content-Type", "application/x-protobuf")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, fmt, *args):
        pass


def start_otlp_receiver():
    server = ThreadingHTTPServer(("127.0.0.1", OTLP_PORT), OtlpHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    log("OTLP", f"接收器已启动：{OTLP_ENDPOINT}")
    return server


# ────────────────────────── 起停 ──────────────────────────

def port_busy(port=8080) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def clean_env(extra=None):
    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    if extra:
        env.update(extra)
    return env


def start_server(tag, env_extra=None, timeout=420):
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。"
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    log_path = os.path.join(WORK_ROOT, f"{tag}.log")
    cwd = os.path.join(WORK_ROOT, f"cwd-{tag}")
    os.makedirs(cwd, exist_ok=True)
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev"]
    effective_env = dict(env_extra or {})
    if LOCAL_DB:
        effective_env = {**LOCAL_DB_ENV, **effective_env}
    log("START", f"起服（{tag}）日志={log_path}"
                 f"{' [本地库 127.0.0.1]' if LOCAL_DB else ' [dev yml 兜底=远端]'}")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT,
                            env=clean_env(effective_env), cwd=cwd)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端提前退出（exit={proc.returncode}），见 {log_path}")
        try:
            if http_get("/health/live")[0] == 200:
                log("START", f"后端就绪 PID={proc.pid}（{tag}）")
                return proc, fh, log_path
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 {timeout}s 未就绪，见 {log_path}")


def stop_server(proc, fh):
    if proc and proc.poll() is None:
        log("STOP", f"kill 本轮 PID {proc.pid}")
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.send_signal(signal.SIGKILL)
            proc.wait(timeout=10)
    if fh:
        fh.close()
    for _ in range(30):
        if not port_busy():
            return
        time.sleep(1)


# ────────────────────────── HTTP 辅助 ──────────────────────────

def http_get(path, headers=None, timeout=20):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def post_json(path, payload, headers=None):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def read_log(path):
    with open(path, "rb") as f:
        return f.read().decode("utf-8", "replace")


def wait_spans(count=1, timeout=40):
    """OTLP 默认走 BatchSpanProcessor（攒批 + 延时），拿到 span 需要等。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if len(received_spans) >= count:
            return True
        time.sleep(1)
    return len(received_spans) >= count


# ────────────────────────── 场景 ──────────────────────────

TRACING_ENV = {
    "PIVOTOS_TRACING_ENABLED": "true",
    "PIVOTOS_TRACING_OTLP_ENDPOINT": OTLP_ENDPOINT,
    "PIVOTOS_TRACING_OTLP_TIMEOUT_MS": "3000",
    "PIVOTOS_TRACING_SAMPLING_PROBABILITY": "1.0",
}


def scenario_bridge():
    """场景 1：入站带 X-Trace-Id → span.traceId 必须等于它（桥接成立）。"""
    tag = "bridge"
    received_spans.clear()
    proc, fh, log_path = start_server(tag, TRACING_ENV)
    try:
        text = read_log(log_path)
        check("S1-1", "[TRACING] X-Trace-Id 桥接传播器已注册" in text, "桥接传播器已装配")
        check("S1-2", f"[TRACING] OTLP 导出器已注册：endpoint={OTLP_ENDPOINT}" in text,
              f"导出器指向本机接收器（{OTLP_ENDPOINT}）")

        trace_id = "9f3c1b2a4d5e6f708192a3b4c5d6e7f8"
        status, headers, _ = http_get("/health/live", {TRACE_HEADER: trace_id})
        check("S1-3", status == 200, f"带 X-Trace-Id 的请求正常（{status}）")
        check("S1-4", wait_spans(1), f"接收器收到 span（实际 {len(received_spans)} 条）")

        matched = [s for s in received_spans if s[0] == trace_id]
        check("S1-5", matched,
              f"span.traceId == 请求头 X-Trace-Id（{trace_id}）；实际收到 "
              f"{[s[0] for s in received_spans]}")

        check("S1-6", headers.get(TRACE_HEADER) == trace_id,
              f"响应头回写同一个 X-Trace-Id（{headers.get(TRACE_HEADER)}）")
    finally:
        stop_server(proc, fh)


def scenario_noheader():
    """场景 2：不带头 → 响应头里的 id（TraceIdFilter 生成）必须等于 span.traceId。

    这条才是「桥接是否真闭环」的判据：只认请求头的话，普通请求（占绝大多数）
    会出现日志一个 ID、Jaeger 另一个 ID。
    """
    tag = "noheader"
    received_spans.clear()
    proc, fh, log_path = start_server(tag, TRACING_ENV)
    try:
        status, headers, _ = http_get("/health/live")
        check("S2-1", status == 200, "普通请求正常")
        resp_trace = headers.get(TRACE_HEADER)
        check("S2-2", resp_trace and len(resp_trace) == 32,
              f"响应头带回 32 位 traceId（{resp_trace}）")
        check("S2-3", wait_spans(1), f"接收器收到 span（实际 {len(received_spans)} 条）")

        trace_ids = {s[0] for s in received_spans}
        check("S2-4", resp_trace in trace_ids,
              f"span.traceId 与响应头一致（响应头={resp_trace}，span={sorted(trace_ids)}）")
    finally:
        stop_server(proc, fh)


def scenario_disabled():
    """场景 3（负向）：总开关不给 → 收不到任何 span，且业务照常。"""
    tag = "disabled"
    received_spans.clear()
    proc, fh, log_path = start_server(tag, None)
    try:
        text = read_log(log_path)
        check("S3-1", "[TRACING]" not in text, "未开启时日志无 [TRACING] 装配信息（零行为变化）")

        status, _, _ = http_get("/health/live")
        check("S3-2", status == 200, "业务照常")

        time.sleep(12)  # 等过 BatchSpanProcessor 的一个批次窗口
        check("S3-3", not received_spans,
              f"未开启时不应有任何 span（实际 {len(received_spans)} 条）")
    finally:
        stop_server(proc, fh)


def scenario_mdc():
    """场景 4：登录后请求 → 日志里出现真实 userId（而不是 u=-）。"""
    tag = "mdc"
    received_spans.clear()
    proc, fh, log_path = start_server(tag, TRACING_ENV)
    try:
        status, body = post_json("/system/auth/login",
                                 {"username": "admin", "password": "admin123"})
        check("S4-1", status == 200 and body.get("code") == 0, f"登录成功（{body}）")
        token = body["data"]["token"]

        status, _, _ = http_get("/system/menu/routers", {"Authorization": token})
        check("S4-2", status == 200, "带 token 请求正常")

        time.sleep(2)
        text = read_log(log_path)
        # v3.2.0 起身份段从 [u=1,t=12] 改为独立 kv 字段 userId=1 tenantId=12
        check("S4-3", re.search(r"\buserId=(?!-)\d", text) is not None,
              "日志出现真实 userId（MDC 身份维度已生效）；若恒为 userId=- 说明 MdcContextFilter 未装配")
    finally:
        stop_server(proc, fh)


def scenario_jaeger():
    """场景 5（Jaeger 真机 · UI 层）：span 打进**真的 Jaeger**，并按 traceId 从 Query API 查回来。

    与场景 1~2 的区别：那两个用的是脚本内置的 OTLP 接收器，只能证明「导出通路通了」；
    本场景证明的是**运维在 Jaeger UI 上真的能搜到这条 trace，且父子层级是对的**。

    父子层级是 v3.2.0 的重点：入站同时带 X-Trace-Id 与 W3C traceparent（含上游 spanId），
    导出后 Jaeger 侧该 span 的 CHILD_OF 引用必须指向 traceparent 里的那个 spanId ——
    修之前父 spanId 是合成随机值，UI 上父子层级是假的（看得到同一个 traceId，但连不上）。
    """
    tag = "jaeger"
    received_spans.clear()
    jaeger_env = dict(TRACING_ENV)
    jaeger_env["PIVOTOS_TRACING_OTLP_ENDPOINT"] = JAEGER_OTLP_ENDPOINT
    proc, fh, log_path = start_server(tag, jaeger_env)
    try:
        text = read_log(log_path)
        check("S5-1", f"[TRACING] OTLP 导出器已注册：endpoint={JAEGER_OTLP_ENDPOINT}" in text,
              f"导出器指向真 Jaeger（{JAEGER_OTLP_ENDPOINT}）")

        # ⚠️ traceId / 上游 spanId 必须**每轮随机**：
        # Jaeger all-in-one 默认内存存储会保留上一轮的 span，用固定 ID 时
        # 「改动没生效」也会读到上一轮的正确数据 —— 断言恒绿，等于没断言。
        # 这是本场景第一版就踩到的假绿（变异后仍 PASS）。
        trace_id = uuid.uuid4().hex
        upstream_span_id = uuid.uuid4().hex[:16]
        traceparent = f"00-{trace_id}-{upstream_span_id}-01"
        status, _, _ = http_get("/health/live", {TRACE_HEADER: trace_id, "traceparent": traceparent})
        check("S5-2", status == 200, f"带双头的请求正常（{status}）")

        payload = wait_jaeger_trace(trace_id)
        check("S5-3", payload is not None,
              f"Jaeger Query API 能按 traceId 查到 trace（{trace_id}）")

        spans = []
        if payload:
            for tr in payload.get("data") or []:
                spans.extend(tr.get("spans") or [])
        check("S5-4", bool(spans), f"trace 里有 span（实际 {len(spans)} 个）")

        parent_refs = []
        for sp in spans:
            for ref in sp.get("references") or []:
                if ref.get("refType") == "CHILD_OF":
                    parent_refs.append(ref.get("spanID"))
        check("S5-5", upstream_span_id in parent_refs,
              f"父 spanId 取 traceparent 的真实值（期望 {upstream_span_id}，实际 {parent_refs}）"
              f"—— 这是「父子层级真实」的硬判据，合成值会指向一个不存在的 span")

        # S5-8 是唯一能区分「对齐逻辑生效」与「W3C 自己碰巧对了」的断言：
        # 两头 traceId **故意不一致** —— 若只是 Boot 的 W3C propagator 在起作用，
        # 落到 Jaeger 的会是 traceparent 的 traceId；只有本仓的对齐逻辑（以 X-Trace-Id
        # 为准重写 traceId、保留 traceparent 的真实 spanId）才会产出下面这个组合。
        conflict_trace = uuid.uuid4().hex
        conflict_upstream = uuid.uuid4().hex[:16]
        w3c_trace = uuid.uuid4().hex
        status, _, _ = http_get("/health/live", {
            TRACE_HEADER: conflict_trace,
            "traceparent": f"00-{w3c_trace}-{conflict_upstream}-01",
        })
        check("S5-8a", status == 200, f"冲突双头请求正常（{status}）")
        payload2 = wait_jaeger_trace(conflict_trace)
        spans2 = []
        if payload2:
            for tr in payload2.get("data") or []:
                spans2.extend(tr.get("spans") or [])
        parents2 = [ref.get("spanID")
                    for sp in spans2
                    for ref in (sp.get("references") or [])
                    if ref.get("refType") == "CHILD_OF"]
        check("S5-8", bool(payload2) and conflict_upstream in parents2,
              f"traceId 取 X-Trace-Id（{conflict_trace[:8]}…）且父 spanId 仍取 traceparent 的"
              f"真实值（{conflict_upstream}）；实际父引用 {parents2}")

        services = jaeger_get("/api/services").get("data") or []
        check("S5-6", bool(services),
              f"Jaeger 已登记服务（{services}）——证明 span 真的进了 Jaeger 存储而非只被接收")

        ui_status, ui_body = http_raw(JAEGER_URL + "/search", timeout=10)
        check("S5-7", ui_status == 200 and ("Jaeger" in ui_body or "jaeger" in ui_body),
              f"Jaeger UI 层可达（HTTP {ui_status}）")
    finally:
        stop_server(proc, fh)


SCENARIOS = {
    "bridge": scenario_bridge,
    "noheader": scenario_noheader,
    "disabled": scenario_disabled,
    "mdc": scenario_mdc,
    "jaeger": scenario_jaeger,
}


def jaeger_get(path, timeout=15):
    req = urllib.request.Request(JAEGER_URL.rstrip("/") + path)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def wait_jaeger_trace(trace_id, timeout=60):
    """Jaeger 落库是异步的（BatchSpanProcessor + 存储写入），必须轮询而不是 sleep 一刀切。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            payload = jaeger_get(f"/api/traces/{trace_id}")
            if payload.get("data"):
                return payload
        except Exception:
            pass
        time.sleep(3)
    return None


def http_raw(url, timeout=10):
    req = urllib.request.Request(url)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.getcode(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser(description="链路追踪 OTLP E2E")
    ap.add_argument("--only", choices=list(SCENARIOS), help="只跑指定场景")
    ap.add_argument("--jaeger-url", help="Jaeger Query 地址（如 http://127.0.0.1:16686）；"
                                          "给了才会跑 jaeger 场景（真机 UI 层验证）")
    ap.add_argument("--jaeger-otlp", default="http://127.0.0.1:4318/v1/traces",
                    help="Jaeger 的 OTLP HTTP 接收端点（默认 http://127.0.0.1:4318/v1/traces）")
    ap.add_argument("--local-db", action="store_true",
                    help="把数据源与 Redis 指到本机 127.0.0.1（docker 起的 mysql:8.4 / redis:8）。"
                         "远端开发服务器不可达时必须加，否则「收不到 span」是环境不通而非桥接失败。")
    args = ap.parse_args()

    global LOCAL_DB, JAEGER_URL, JAEGER_OTLP_ENDPOINT
    LOCAL_DB = args.local_db
    JAEGER_URL = args.jaeger_url
    JAEGER_OTLP_ENDPOINT = args.jaeger_otlp
    os.makedirs(WORK_ROOT, exist_ok=True)
    if not os.path.exists(JAR):
        raise RuntimeError(f"找不到 fat jar：{JAR}（先 mvn clean package）")
    if args.only == "jaeger" and not JAEGER_URL:
        raise RuntimeError("jaeger 场景必须给 --jaeger-url（Jaeger Query 地址）")

    plan = [args.only] if args.only else ["bridge", "noheader", "disabled", "mdc"]
    # --only 已显式指定时不再追加（否则 --only jaeger + --jaeger-url 会把该场景跑两遍）
    if JAEGER_URL and not args.only:
        plan.append("jaeger")
    # ⚠️ 内置接收器占 4318，而 Jaeger 的 OTLP HTTP 接收端也是 4318 ——
    #    同时跑会 bind 失败（表现为「Jaeger 收不到 span」而不是「端口冲突」，极难归因）。
    #    只跑 jaeger 场景时就不起内置接收器。
    server = None
    if any(n != "jaeger" for n in plan):
        server = start_otlp_receiver()
    failed = []
    try:
        for name in plan:
            log("SCENARIO", f"===== {name} =====")
            try:
                SCENARIOS[name]()
            except Exception as e:
                failed.append((name, e))
                log("ERROR", f"{name} 场景异常：{e}")
    finally:
        if server:
            server.shutdown()

    log("SUMMARY", f"断言 {len(results)} 条，通过 {sum(1 for _, ok in results if ok)} 条")
    for tag, ok in results:
        if not ok:
            log("FAILED", tag)
    if failed:
        for name, e in failed:
            log("SCENARIO-FAIL", f"{name}: {e}")
        sys.exit(1)
    log("RESULT", "ALL PASS")


if __name__ == "__main__":
    main()
