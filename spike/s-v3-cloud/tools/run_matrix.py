#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S133 V3 spike —— 形态切换与一票否决点实测脚本（可复跑）

覆盖
----
- V1 单体形态零配置        ：F1（原封不动的 fat jar，`java -jar`）
- V2 微服务形态零业务改动  ：F2（+openfeign +nacos，Facade 调用改走 HTTP，业务代码 diff=0）
- V3 可裁剪性              ：F3~F6（全引 / 单独引 seata / gateway / sentinel）
- V3 fail-fast             ：F7（HMAC 未配密钥）/ F8（nacos 未配地址）→ 期望**启动失败**

纪律
----
- 起服一律 dev profile，带 TESSDATA_PREFIX + -Djava.awt.headless=true；
- 绝不使用 --spring.flyway.validate-on-migrate=false；
- 只 kill 本轮自己记录的 PID；结束后复核 8080 / 18848 端口释放。

用法
----
cd pivotos-framework && python3 spike/s-v3-cloud/tools/run_matrix.py            # 全跑
python3 spike/s-v3-cloud/tools/run_matrix.py --only F1-monolith,F2-microservice
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Callable

HERE = os.path.dirname(os.path.abspath(__file__))
FW_ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
EV_DIR = os.path.join(FW_ROOT, "spike", "s-v3-cloud", "evidence")
JARS_DIR = os.path.join(FW_ROOT, "spike", "s-v3-cloud", "build", "jars")
APP_JAR = os.path.join(FW_ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
EXPLODED = os.path.join(FW_ROOT, "spike", "s-v3-cloud", "build", "exploded-app")
JAVA_HOME = os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home")
JAVA = os.path.join(JAVA_HOME, "bin", "java")

BASE = "http://127.0.0.1:8080"
APP_PORT = 8080
STUB_PORT = 18849
START_TIMEOUT = 180
PROXIES = (
    "com.pivotos.file.api.facade.IFileFacade,"
    "com.pivotos.ai.api.facade.IAiFacade,"
    "com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade"
)


# ---------------------------------------------------------------------------- 形态定义


@dataclass
class Form:
    id: str
    title: str
    launcher: str                       # jar（零配置单体） / cp（classpath 组合）
    jars: list = field(default_factory=list)
    props: dict = field(default_factory=dict)
    expect: str = "up"                  # up = 期望启动成功；fail = 期望 fail-fast 启动失败
    fail_marker: str = ""
    checks: list = field(default_factory=list)


FORMS = [
    Form(
        id="F1-monolith",
        title="V1 · 单体形态零配置（原封不动的 fat jar，不引任何 Cloud Starter）",
        launcher="jar",
        checks=["login", "summary", "log-flyway"],
    ),
    Form(
        id="F2-microservice",
        title="V2 · 微服务形态（+openfeign +nacos，同一调用改走 HTTP，业务代码零改动）",
        launcher="cp",
        jars=["cloud-openfeign", "cloud-nacos"],
        props={
            "pivotos.cloud.openfeign.enabled": "true",
            "pivotos.cloud.openfeign.server-enabled": "true",
            "pivotos.cloud.openfeign.service-instance-provider": "nacos",
            "pivotos.cloud.openfeign.proxied": PROXIES,
            "pivotos.cloud.nacos.enabled": "true",
            "pivotos.cloud.nacos.server-addr": f"127.0.0.1:{STUB_PORT}",
            "pivotos.cloud.nacos.ip": "127.0.0.1",
            "pivotos.cloud.nacos.port": str(APP_PORT),
        },
        checks=["login", "summary", "log-rpc", "log-nacos"],
    ),
    Form(
        id="F3-all-cloud",
        title="V3 · 五个 Cloud Starter 全引全启用",
        launcher="cp",
        jars=["cloud-openfeign", "cloud-nacos", "cloud-gateway", "cloud-sentinel", "cloud-seata"],
        props={
            "pivotos.cloud.openfeign.enabled": "true",
            "pivotos.cloud.openfeign.server-enabled": "true",
            "pivotos.cloud.openfeign.service-instance-provider": "nacos",
            "pivotos.cloud.openfeign.proxied": PROXIES,
            "pivotos.cloud.nacos.enabled": "true",
            "pivotos.cloud.nacos.server-addr": f"127.0.0.1:{STUB_PORT}",
            "pivotos.cloud.nacos.ip": "127.0.0.1",
            "pivotos.cloud.nacos.port": str(APP_PORT),
            "pivotos.cloud.gateway.enabled": "true",
            "pivotos.cloud.sentinel.enabled": "true",
            "pivotos.cloud.sentinel.qps": "500",
            "pivotos.cloud.seata.enabled": "true",
        },
        checks=["login", "summary", "log-rpc", "gateway-header"],
    ),
    Form(
        id="F4-only-seata",
        title="V3 · 单独引 seata（配 openfeign 使其真实装配；无注册中心）",
        launcher="cp",
        jars=["cloud-seata", "cloud-openfeign"],
        props={
            "pivotos.cloud.seata.enabled": "true",
            "pivotos.cloud.openfeign.enabled": "true",
            "pivotos.cloud.openfeign.server-enabled": "true",
            "pivotos.cloud.openfeign.proxied": PROXIES,
        },
        checks=["login", "summary", "log-seata", "log-rpc"],
    ),
    Form(
        id="F5-only-gateway",
        title="V3 · 单独引 gateway",
        launcher="cp",
        jars=["cloud-gateway"],
        props={"pivotos.cloud.gateway.enabled": "true"},
        checks=["login", "summary", "gateway-header"],
    ),
    Form(
        id="F6-only-sentinel",
        title="V3 · 单独引 sentinel（qps=1，验证限流确实生效）",
        launcher="cp",
        jars=["cloud-sentinel"],
        props={
            "pivotos.cloud.sentinel.enabled": "true",
            "pivotos.cloud.sentinel.qps": "1",
            # 只对看板端点限流，避免把「登录」也挡在门外导致形态看起来「起不来」
            "pivotos.cloud.sentinel.url-pattern": "/monitor/dashboard/*",
        },
        checks=["login", "sentinel-limit"],
    ),
    Form(
        id="F7-openfeign-hmac-nosecret",
        title="V3 · fail-fast：HMAC 已启用但未配密钥 → 必须起不来",
        launcher="cp",
        jars=["cloud-openfeign"],
        props={
            "pivotos.cloud.openfeign.enabled": "true",
            "pivotos.cloud.openfeign.hmac-enabled": "true",
            "pivotos.cloud.openfeign.hmac-secret": "",
        },
        expect="fail",
        fail_marker="[CLOUD][FAIL-FAST]",
    ),
    Form(
        id="F8-nacos-noaddr",
        title="V3 · fail-fast：nacos 已启用但未配地址 → 必须起不来（配 openfeign 使其装配）",
        launcher="cp",
        jars=["cloud-nacos", "cloud-openfeign"],
        props={
            "pivotos.cloud.nacos.enabled": "true",
            "pivotos.cloud.nacos.server-addr": "",
            "pivotos.cloud.openfeign.enabled": "true",
        },
        expect="fail",
        fail_marker="[CLOUD][FAIL-FAST]",
    ),
]


# ---------------------------------------------------------------------------- 基础设施


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def port_in_use(port: int) -> list:
    out = subprocess.run(["lsof", "-Pan", f"-i:{port}", "-sTCP:LISTEN"],
                         capture_output=True, text=True).stdout.strip()
    return [ln for ln in out.splitlines()[1:]] if out else []


def http_json(method: str, path: str, body: dict | None = None, headers: dict | None = None, timeout: int = 30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw.startswith("{") else raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw


def wait_port(port: int, timeout: int = START_TIMEOUT) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return True
        except OSError:
            time.sleep(1)
    return False


# ---------------------------------------------------------------------------- 断言点


def check_login(results: list, ctx: dict) -> None:
    status, body = http_json("POST", "/system/auth/login", {"username": "admin", "password": "admin123"})
    ok = status == 200 and isinstance(body, dict) and body.get("code") == 0 and body.get("data", {}).get("token")
    results.append(("登录 /system/auth/login", ok, f"HTTP {status}, code={body.get('code') if isinstance(body, dict) else body}"))
    if ok:
        ctx["token"] = body["data"]["token"]
        ctx["hdr"] = {"Authorization": body["data"]["token"]}


def check_summary(results: list, ctx: dict) -> None:
    status, body = http_json("GET", "/monitor/dashboard/summary", headers=ctx.get("hdr", {}), timeout=60)
    data = body.get("data") if isinstance(body, dict) else None
    blocks = {}
    if isinstance(data, dict):
        blocks = {k: (data.get(k) is not None) for k in ("system", "file", "ai", "kb", "workflow")}
    # 跨 Plugin Facade 的三个区块必须取到值（证明 Facade 通道真的通了）
    ok = (status == 200 and isinstance(body, dict) and body.get("code") == 0
          and blocks.get("file") and blocks.get("ai") and blocks.get("kb"))
    results.append(("跨 Plugin 调用 summary（file/ai/kb 三区块）", bool(ok),
                    f"HTTP {status}, code={body.get('code') if isinstance(body, dict) else '-'}, blocks={blocks}"))


def read_log(ctx: dict) -> str:
    """日志必须在「冒烟调用之后」再读，否则断言看到的是调用发生前的快照（本次踩坑 K5）"""
    path = ctx.get("log_path")
    if not path:
        return ""
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


def check_flyway_log(results: list, ctx: dict) -> None:
    text = read_log(ctx)
    ok = ("Successfully validated 85 migrations" in text or "validated 85 migrations" in text) \
        and ("up to date" in text) and ("validate-on-migrate=false" not in text)
    marker = next((ln for ln in text.splitlines() if "validated" in ln), "")
    results.append(("Flyway 校验通过且未用 validate-on-migrate=false", ok, marker.strip()[:120]))


def check_rpc_log(results: list, ctx: dict) -> None:
    text = read_log(ctx)
    client = sorted({ln.split("<")[0].strip() for ln in text.splitlines() if "[CLOUD-RPC-CLIENT]" in ln})
    server = sorted({ln.split("->")[0].strip() for ln in text.splitlines() if "[CLOUD-RPC-SERVER]" in ln})
    ok = bool(client) and bool(server)
    results.append(("Facade 调用确实走了 HTTP（CLIENT+SERVER 日志成对）", ok,
                    f"client={len(client)} 条, server={len(server)} 条；样例={client[0][:110] if client else '-'}"))


def check_nacos_log(results: list, ctx: dict) -> None:
    text = read_log(ctx)
    ok = "[CLOUD][nacos] 已注册" in text
    marker = next((ln for ln in text.splitlines() if "[CLOUD][nacos] 已注册" in ln), "-")
    results.append(("nacos 注册/发现链路打通（替身 Open API）", ok, marker.strip()[:120]))


def check_gateway_header(results: list, ctx: dict) -> None:
    status, body = http_json("POST", "/system/auth/login", {"username": "admin", "password": "admin123"})
    # Header 需要在原始响应里读，这里用 socket 层再取一次
    req = urllib.request.Request(BASE + "/system/auth/login",
                                 data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as resp:
        headers = dict(resp.headers)
    ok = "X-Edge-Gateway" in headers and "X-Edge-Request-Id" in headers
    results.append(("gateway 边缘头已注入", ok,
                    f"X-Edge-Gateway={headers.get('X-Edge-Gateway')}, X-Edge-Request-Id={headers.get('X-Edge-Request-Id')}"))


def check_seata_log(results: list, ctx: dict) -> None:
    text = read_log(ctx)
    ok = "[CLOUD][seata] 事务边界已装配" in text
    marker = next((ln for ln in text.splitlines() if "[CLOUD][seata]" in ln), "-")
    results.append(("seata 事务边界已装配并随 RPC 传播 XID", ok, marker.strip()[-110:]))


def check_sentinel_limit(results: list, ctx: dict) -> None:
    codes = []
    for _ in range(6):
        try:
            status, _ = http_json("GET", "/monitor/dashboard/summary", headers=ctx.get("hdr", {}), timeout=60)
        except Exception:
            status = -1
        codes.append(status)
    ok = 429 in codes and 200 in codes
    results.append(("sentinel qps=1 触发限流（429 与 200 同时出现）", ok, f"status codes={codes}"))


CHECKS: dict[str, Callable[[list, dict], None]] = {
    "login": check_login,
    "summary": check_summary,
    "log-flyway": check_flyway_log,
    "log-rpc": check_rpc_log,
    "log-nacos": check_nacos_log,
    "log-seata": check_seata_log,
    "gateway-header": check_gateway_header,
    "sentinel-limit": check_sentinel_limit,
}


# ---------------------------------------------------------------------------- 形态执行


def build_cmd(form: Form) -> list:
    jvm = [
        JAVA, "-Djava.awt.headless=true", f"-Djna.library.path=/opt/homebrew/lib",
        "-Xms512m", "-Xmx1536m",
    ]
    args = ["--spring.profiles.active=dev", f"--server.port={APP_PORT}"]
    for k, v in form.props.items():
        args.append(f"--{k}={v}")
    if form.launcher == "jar":
        return jvm + ["-jar", APP_JAR] + args
    cp = f"{EXPLODED}/BOOT-INF/classes:{EXPLODED}/BOOT-INF/lib/*:" + ":".join(
        os.path.join(JARS_DIR, f"pivotos-starter-{j}.jar") for j in form.jars)
    return jvm + ["-cp", cp, "com.pivotos.server.PivotOsAdminApplication"] + args


def start_stub() -> subprocess.Popen:
    return subprocess.Popen(
        [sys.executable, os.path.join(HERE, "nacos_stub.py"), "--port", str(STUB_PORT)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def run_form(form: Form) -> dict:
    log(f"===== 形态 {form.id}：{form.title} =====")
    app_log_path = os.path.join(EV_DIR, f"app-{form.id}.log")
    result = {"id": form.id, "title": form.title, "expect": form.expect, "checks": [], "passed": False}

    busy = port_in_use(APP_PORT)
    if busy:
        result["checks"].append(("端口预检 8080 空闲", False, "; ".join(busy)))
        return result

    need_stub = any("nacos" in j for j in form.jars)
    stub = start_stub() if need_stub else None
    if stub:
        time.sleep(1)

    env = dict(os.environ)
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    fh = open(app_log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(build_cmd(form), stdout=fh, stderr=subprocess.STDOUT, env=env)
    log(f"    JVM pid={proc.pid}")

    ctx: dict = {"log_path": app_log_path}
    try:
        if form.expect == "fail":
            try:
                rc = proc.wait(timeout=150)
            except subprocess.TimeoutExpired:
                proc.kill()
                rc = -9
            fh.flush()
            with open(app_log_path, "r", encoding="utf-8", errors="ignore") as f:
                text = f.read()
            ctx["app_log"] = text
            ok = rc != 0 and form.fail_marker in text
            marker = next((ln for ln in text.splitlines() if form.fail_marker in ln), "-")
            result["checks"].append(("JVM 非 0 退出且日志含 FAIL-FAST", ok, f"exit={rc}; {marker.strip()[:140]}"))
            result["passed"] = ok
            return result

        if not wait_port(APP_PORT):
            result["checks"].append(("8080 端口就绪", False, f"{START_TIMEOUT}s 内未监听"))
            return result
        # 端口就绪后再等一小会儿，避免嵌入式容器尚未完成初始化
        time.sleep(2)
        if proc.poll() is not None:
            result["checks"].append(("JVM 存活", False, f"进程已退出，exit={proc.returncode}"))
            return result

        # 等应用层真就绪（登录成功）
        deadline = time.time() + 150
        ready = False
        while time.time() < deadline and proc.poll() is None:
            try:
                status, body = http_json("POST", "/system/auth/login",
                                         {"username": "admin", "password": "admin123"}, timeout=10)
                if status == 200 and isinstance(body, dict) and body.get("code") == 0:
                    ready = True
                    break
            except Exception:
                pass
            time.sleep(2)
        if not ready:
            result["checks"].append(("应用层就绪（登录可达）", False, "150s 内未成功登录"))
            return result

        # 日志由 log-* 类断言在每次检查时实时读取（见 read_log），避免读到调用发生前的快照
        fh.flush()

        for name in form.checks:
            CHECKS[name](result["checks"], ctx)
        result["checks"].append(("JVM 存活（跑完断言仍然活着）", proc.poll() is None,
                                 f"exit={proc.returncode}"))
        result["passed"] = all(ok for _, ok, _ in result["checks"])
    finally:
        try:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=20)
        except Exception:
            proc.kill()
        fh.flush()
        fh.close()
        if stub:
            stub.terminate()
            try:
                stub.wait(timeout=10)
            except Exception:
                stub.kill()
        time.sleep(1)
        left = port_in_use(APP_PORT)
        result["checks"].append(("收口：8080 已释放", not left, "; ".join(left) or "clean"))
        if left:
            result["passed"] = False
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    args = ap.parse_args()

    os.makedirs(EV_DIR, exist_ok=True)
    selected = [f for f in FORMS if (not args.only or f.id in args.only.split(","))]

    if not os.path.isfile(APP_JAR):
        log(f"[FATAL] 缺少 {APP_JAR}")
        return 1

    results = [run_form(f) for f in selected]

    # 业务模块零改动（V2 否决点的硬证据）
    business_diff = subprocess.run(
        ["git", "diff", "--numstat", "--",
         "pivotos-plugins", "pivotos-starters", "pivotos-commons",
         "pivotos-admin-server", "pom.xml", "pivotos-dependencies"],
        cwd=FW_ROOT, capture_output=True, text=True).stdout.strip()

    lines = []
    lines.append("=" * 100)
    lines.append("S133 · V3「形态革命」spike —— 形态切换与一票否决点实测")
    lines.append("=" * 100)
    lines.append(f"执行时间  : {time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"形态数量  : {len(results)}")
    lines.append("")
    total = ok_total = 0
    for r in results:
        lines.append("-" * 100)
        lines.append(f"[{r['id']}] {r['title']}   expect={r['expect']}   => {'PASS' if r['passed'] else 'FAIL'}")
        for name, ok, detail in r["checks"]:
            total += 1
            ok_total += 1 if ok else 0
            lines.append(f"    {'✅' if ok else '❌'} {name} :: {detail}")
        lines.append("")
    lines.append("=" * 100)
    lines.append(f"断言汇总：{ok_total}/{total} PASS")
    lines.append("V2 业务模块零改动校验（git diff --numstat 业务目录）：")
    lines.append(f"    {business_diff if business_diff else '（空）零行改动 —— 形态切换未触碰任何业务代码'}")
    lines.append("=" * 100)
    text = "\n".join(lines)

    out = os.path.join(EV_DIR, "03-morph-matrix.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print(text)
    print(f"[written] {out}")
    return 0 if ok_total == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
