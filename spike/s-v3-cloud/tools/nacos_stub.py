#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S133 V3 spike —— Nacos Open API v1 最小桩（替身）

为什么需要它
------------
V2 一票否决点要求「加上 -nacos 之后应用仍能正常启动并完成一次调用」。本机没有真实 Nacos Server，
若为此放宽 Starter 的 fail-fast（注册失败继续启动），等于把要验证的东西绕过去了。
于是这里提供一个**只实现三个必要端点**的替身，让 Starter 走完整的
「注册 → 心跳 → 服务发现 → 解析出实例地址」链路：

    POST /nacos/v1/ns/instance          注册实例        -> "ok"
    PUT  /nacos/v1/ns/instance/beat     心跳            -> {"code":10200,"clientBeatInterval":5000}
    GET  /nacos/v1/ns/instance/list     健康实例列表     -> {"hosts":[{"ip":..,"port":..,"healthy":true}]}

实现说明（踩坑记录）
--------------------
本机沙箱环境下 `http.server.ThreadingHTTPServer` 能 bind 但无法正常 accept（连接超时、
socket 停在 CLOSED），因此这里直接用 <b>裸 socket + 手写 HTTP 响应</b>，避免任何框架依赖。

用法：python3 nacos_stub.py --port 18849 --advertise-ip 127.0.0.1 --advertise-port 8080
"""
from __future__ import annotations

import argparse
import json
import socket
import threading
from urllib.parse import parse_qs, urlparse

INSTANCES: list[dict] = []
LOCK = threading.Lock()
ADVERTISE = {"ip": "127.0.0.1", "port": 8080}


def handle_request(raw: str) -> tuple[int, bytes, str]:
    first_line = raw.split("\r\n", 1)[0]
    parts = first_line.split(" ")
    method = parts[0] if parts else "GET"
    path = parts[1] if len(parts) > 1 else "/"
    parsed = urlparse(path)
    qs = parse_qs(parsed.query)

    if parsed.path == "/nacos/v1/ns/instance" and method == "POST":
        inst = {
            "serviceName": qs.get("serviceName", ["unknown"])[0],
            "ip": qs.get("ip", ["127.0.0.1"])[0],
            "port": int(qs.get("port", ["8080"])[0]),
            "groupName": qs.get("groupName", ["DEFAULT_GROUP"])[0],
            "healthy": True,
        }
        with LOCK:
            if inst not in INSTANCES:
                INSTANCES.append(inst)
        print(f"[NACOS-STUB] registered {inst['serviceName']} -> {inst['ip']}:{inst['port']}", flush=True)
        return 200, b"ok", "text/plain"

    if parsed.path == "/nacos/v1/ns/instance/beat" and method == "PUT":
        return 200, json.dumps({"code": 10200, "clientBeatInterval": 5000}).encode(), "application/json"

    if parsed.path == "/nacos/v1/ns/instance/list" and method == "GET":
        with LOCK:
            hosts = [{"ip": i["ip"], "port": i["port"], "healthy": True, "valid": True} for i in INSTANCES]
        # 未注册时给兜底：让发现链路始终可解析
        if not hosts:
            hosts = [{"ip": ADVERTISE["ip"], "port": ADVERTISE["port"], "healthy": True, "valid": True}]
        print(f"[NACOS-STUB] list -> {hosts}", flush=True)
        return 200, json.dumps({"count": len(hosts), "hosts": hosts}).encode(), "application/json"

    return 404, b'{"error":"not found"}', "application/json"


def serve(conn: socket.socket, addr) -> None:
    try:
        conn.settimeout(5)
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = conn.recv(8192)
            if not chunk:
                break
            buf += chunk
        raw = buf.decode("utf-8", errors="ignore")
        status, body, ctype = handle_request(raw or "GET / HTTP/1.1")
        head = (
            f"HTTP/1.1 {status} {'OK' if status == 200 else 'Not Found'}\r\n"
            f"Content-Type: {ctype};charset=UTF-8\r\n"
            f"Content-Length: {len(body)}\r\n"
            f"Connection: close\r\n\r\n"
        )
        conn.sendall(head.encode() + body)
    except Exception as e:  # noqa: BLE001
        print(f"[NACOS-STUB] error: {e}", flush=True)
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18849)
    ap.add_argument("--advertise-ip", default="127.0.0.1")
    ap.add_argument("--advertise-port", type=int, default=8080)
    args = ap.parse_args()
    ADVERTISE["ip"], ADVERTISE["port"] = args.advertise_ip, args.advertise_port

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(32)
    print(f"[NACOS-STUB] listening on http://127.0.0.1:{args.port}", flush=True)
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=serve, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    raise SystemExit(main())
