#!/usr/bin/env python3
"""V3.1-C · A4 匿名端点审计（自起自停 fat jar，只 kill 本轮 PID）。

做什么：
  起 dev 形态 fat jar → 从 /v3/api-docs 取全部 path → 逐个匿名实打 GET →
  按「响应体出现成功码」判定是否漏出（PivotOS code=0 / warm-flow code=200）→
  与 ALWAYS_ANONYMOUS 白名单比对，多一个就 FAIL。

为什么做成脚本而不是一次性取证：
  §10-K 实证的「9/90 匿名漏出」是人工扫的，且**只打了无参 GET**——
  带必填 query 参数的端点（如 /migration/step/list?taskId=1）根本没被覆盖。
  漏鉴权是「注解驱动 + 业务代码兜底」双轨制下的零信号缺陷，
  唯一可靠的兜底是**每次发版前全量匿名实打**，故本脚本是长期回归资产。

两种跑法：
    python3 scripts/e2e/anonymous_endpoint_audit.py             # 断言模式（有漏出即非零退出）
    python3 scripts/e2e/anonymous_endpoint_audit.py --survey    # 只勘察，打印明细不断言

判定口径（照抄 §10-K，勿改）：
  · 漏出 = 匿名响应体里出现成功码（code=0 或 code=200，或 success=true）
  · 不存在的路径是 HTTP 200 + code=1004（全局异常处理），**按状态码断言会误判**
  · 被拦的形态是 HTTP 200 + code=1002（未登录），不是 401
"""
import argparse
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAR = os.path.join(ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
WORK_ROOT = "/tmp/pivotos-v31c-e2e"

# 设计上就应当匿名、且**会被本脚本扫到**的端点（每一条都必须写清理由）。
# 登录类端点（/system/auth/login、/app/**/login、/mini/**/login 等）是 POST，不在本脚本的 GET 扫描范围，
# 它们的「匿名是设计如此」由 ArchUnit A11 的 ANONYMOUS_ENDPOINTS 登记守着，两边分工不重复。
ALWAYS_ANONYMOUS = {
    "/health/live": "存活探针：Nginx/k8s 必须匿名打（V3.1-B A2）",
    "/health/ready": "就绪探针：同上；匿名只给 status+checks，明细需登录",
    # V3.1-F · P1-B8-1：Prometheus server 抓取不带登录态，端点必须匿名可达。
    # 代价是「指标全貌对任何能访问该端口的人可见」，故生产必须在网络层限内网，
    # 或用 PIVOTOS_METRICS_PROMETHEUS_ENABLED=false 把端点整个摘掉。
    "/actuator/prometheus": "Prometheus 抓取端点：抓取方无登录态，必须匿名；生产须限内网或整体摘除",
}

results = []


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(tag, cond, msg):
    results.append((tag, bool(cond)))
    if cond:
        log("PASS", f"{tag} {msg}")
    else:
        log("FAIL", f"{tag} {msg}")


# ────────────────────────── 起停 ──────────────────────────

def port_busy(port=8080) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def clean_env(extra=None):
    """避免上一段遗留的 PIVOTOS_* 污染本次起服（连接串带 createDatabaseIfNotExist=true，
    连错库也不报错，是本项目最容易踩的坑）。"""
    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    if extra:
        env.update(extra)
    return env


def start_server(timeout=240):
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。请先确认归属："
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    cwd = os.path.join(WORK_ROOT, "cwd")
    os.makedirs(cwd, exist_ok=True)
    log_path = os.path.join(WORK_ROOT, "audit.log")
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev"]
    log("START", f"dev 形态起服，日志={log_path}")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=clean_env(), cwd=cwd)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端提前退出（exit={proc.returncode}），见 {log_path}")
        try:
            if http_get("/health/live")[0] == 200:
                log("START", f"后端就绪 PID={proc.pid}")
                return proc, fh
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

def http_get(path, headers=None, timeout=10):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception:
        return 0, b""


def get_json(path, headers=None, timeout=10):
    status, raw = http_get(path, headers, timeout)
    try:
        return status, json.loads(raw)
    except Exception:
        return status, None


def post_json(path, payload, headers=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def login_token():
    status, body = post_json("/system/auth/login", {"username": "admin", "password": "admin123"})
    assert status == 200 and body.get("code") == 0, f"登录失败：{status} {body}"
    return body["data"]["token"]


# ────────────────────────── 扫描 ──────────────────────────

def dummy_value(schema):
    """按 OpenAPI schema 造一个「能过参数绑定」的哑值（不求业务正确，只求不 400）。"""
    t = (schema or {}).get("type")
    if t == "integer":
        return "1"
    if t == "boolean":
        return "true"
    if t == "number":
        return "1"
    return "1"


def collect_get_paths():
    status, doc = get_json("/v3/api-docs", timeout=20)
    if status != 200 or not isinstance(doc, dict) or "paths" not in doc:
        raise RuntimeError(f"取 OpenAPI 失败：HTTP {status}（dev 形态应 200）")
    out = []
    for path, ops in doc["paths"].items():
        get_op = (ops or {}).get("get")
        if not isinstance(get_op, dict):
            continue
        params = []
        for p in get_op.get("parameters") or []:
            if p.get("in") == "query" and p.get("required"):
                params.append((p.get("name"), dummy_value(p.get("schema"))))
        out.append((path, params))
    return sorted(out)


def is_leak(body, http_status):
    """匿名可达判定（HTTP 状态码恒 200，只能读 body，勿按状态码断言）。

    三类都算「匿名可达」：
      · PivotOS 成功：code=0
      · warm-flow 内置端点成功：code=200
      · 无 code 字段的 200 JSON：如 /health/live 的 {"status":"UP"}（探针本就免鉴权）
    被拦的形态是 code=1002（未登录）/ 1004（不存在）/ 1003（无权限），均不算。
    """
    if http_status != 200 or not isinstance(body, dict):
        return False
    if "code" not in body:
        return True
    code = body.get("code")
    if code == 0 or code == 200:
        return True
    return body.get("success") is True


def scan(token):
    """两类端点都扫：
      · 无路径变量 —— 直接打，判定可信；
      · 含路径变量 —— 用哑 id=1 替换后打。**这里存在假阴性**（记录不存在时返回业务非 0 码，
        看起来像「被拦」），故单独列出并在结论里如实标注，不作为「已证明安全」的依据。
    """
    paths = collect_get_paths()
    log("SCAN", f"OpenAPI GET 端点共 {len(paths)} 个")
    plain = [(p, q) for p, q in paths if "{" not in p]
    with_var = [(p, q) for p, q in paths if "{" in p]
    # 哑 id 替换：{xxx} → 1
    var_targets = [(re.sub(r"\{[^}]+\}", "1", p), q) for p, q in with_var]
    log("SCAN", f"无路径变量 {len(plain)} 个（判定可信）；含路径变量 {len(var_targets)} 个（哑 id=1，存在假阴性）")

    leaks, blocked, unknown = [], [], []
    for path, params in plain + var_targets:
        qs = urllib.parse.urlencode(params) if params else ""
        full = path + ("?" + qs if qs else "")
        status, body = get_json(full)
        if is_leak(body, status):
            # 二次取证：登录后同样请求也必须成功，否则说明「匿名成功」只是空数据假象
            st2, body2 = get_json(full, {"Authorization": token})
            leaks.append({"path": full, "code": (body or {}).get("code"),
                          "http": status, "login_ok": bool(is_leak(body2, st2))})
        elif isinstance(body, dict) and body.get("code") is not None:
            blocked.append({"path": full, "code": body.get("code")})
        else:
            unknown.append({"path": full, "http": status})
    return plain, var_targets, leaks, blocked, unknown


# ──────────────────── 定点负向断言（扫描之外的确定性收口） ────────────────────

# 本轮 A4 实证漏出、且已逐个处置的端点（改动前全部匿名 code=0 / code=200）
CLOSED_ENDPOINTS = [
    "/migration/task/page",
    "/migration/step/list?taskId=1",
    "/migration/artifact/list?stepId=1",
    "/ai/chat/kb-options",
    "/warm-flow/query-def",
    "/warm-flow/handler-dict",
    "/warm-flow/handler-type",
    "/warm-flow/handler-result",
    "/warm-flow/handler-feedback",
    "/warm-flow/listener-list",
    "/warm-flow/node-ext",
]


def scenario_negative(token):
    """每条都是负向断言：匿名必须拿不到成功码；并对关键端点补一条「登录仍可用」的正向断言
    （只加鉴权不验证正向，等于没验证——把端点打死也算「匿名不可达」）。"""
    log("SCENARIO", "定点负向断言：11 个实证漏出端点")
    for path in CLOSED_ENDPOINTS:
        status, body = get_json(path)
        check("NEG-" + path, not is_leak(body, status),
              f"匿名 {path} 不得返回成功码，实际 http={status} body={str(body)[:120]}")

    # 正向：登录态下这些端点必须仍然可用（证明是「收口」不是「封死」）
    for path in ["/migration/task/page", "/ai/chat/kb-options"]:
        st2, body2 = get_json(path, {"Authorization": token})
        check("POS-" + path, is_leak(body2, st2),
              f"登录后 {path} 必须仍可用，实际 http={st2} body={str(body2)[:120]}")
    # warm-flow 成功码是 200，且新版设计器依赖它，必须仍能用
    st3, body3 = get_json("/warm-flow/query-def", {"Authorization": token})
    check("POS-/warm-flow/query-def", is_leak(body3, st3),
          f"登录后 /warm-flow/query-def 必须仍可用（新版设计器依赖），实际 http={st3} body={str(body3)[:120]}")

    # 旧设计器入口的语义不能被闸门抢走（V3.1-C 踩坑点：/warm-flow/** 不能匹配 /warm-flow-ui/**）
    status4, body4 = get_json("/warm-flow-ui/index.html")
    check("NEG-legacy-ui", status4 == 410 and isinstance(body4, dict) and body4.get("code") == 4100,
          f"/warm-flow-ui/index.html 必须仍是 410 + code=4100（旧入口已下线），实际 http={status4} body={str(body4)[:120]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--survey", action="store_true", help="只勘察打印明细，不做断言")
    args = ap.parse_args()

    os.makedirs(WORK_ROOT, exist_ok=True)
    proc, fh = start_server()
    failed = 0
    try:
        token = login_token()
        plain, var_targets, leaks, blocked, unknown = scan(token)
        targets = plain + var_targets
        print()
        log("RESULT", f"扫描 {len(targets)} 个 GET：匿名可达 {len(leaks)} / 被拦 {len(blocked)} / 无法判定 {len(unknown)}")
        if leaks:
            print("  ── 匿名漏出清单 ──")
            for x in leaks:
                print(f"    {x['path']}  code={x['code']} http={x['http']} 登录后同样成功={x['login_ok']}")
        if unknown:
            print("  ── 无法判定（非 JSON / 非 200，如实列出）──")
            for x in unknown[:20]:
                print(f"    {x['path']}  http={x['http']}")

        scenario_negative(token)

        if args.survey:
            print()
            print(json.dumps({"scanned_plain": len(plain), "scanned_with_var": len(var_targets),
                              "leaks": [x["path"] for x in leaks]}, ensure_ascii=False, indent=2))
            log("SUMMARY", f"{sum(1 for _, ok in results if ok)}/{len(results)} PASS（勘察模式：仅打印）")
            return 0

        print()
        leak_paths = {x["path"].split("?")[0] for x in leaks}
        unexpected = sorted(leak_paths - set(ALWAYS_ANONYMOUS))
        check("A4-1", not unexpected,
              f"除登记在册的匿名端点外不得有任何漏出；未登记漏出={unexpected}")
        for p in sorted(leak_paths & set(ALWAYS_ANONYMOUS)):
            check("A4-2", True, f"登记匿名（{ALWAYS_ANONYMOUS[p]}）：{p}")
        # 反向守卫：白名单里登记的端点必须真的还能匿名访问（否则 Nginx 探活会被摘流）。
        #
        # ⚠️ 判定必须分两轨，不能一律用 is_leak 的「响应体含成功码」口径：
        # 白名单里不全是 REST JSON 端点——/actuator/prometheus 吐的是 Prometheus 文本
        # （text/plain;version=0.0.4），压根没有 code 字段，按成功码判会被误报成「被拦」。
        #   · JSON 端点   → 沿用 is_leak（成功码 / 无 code 的 200 JSON）
        #   · 非 JSON 端点 → HTTP 200 且 body 非空即可达
        not_reachable = []
        for p in sorted(ALWAYS_ANONYMOUS):
            st, raw = http_get(p)
            text = raw.decode("utf-8", "replace")
            try:
                parsed = json.loads(text)
            except Exception:
                parsed = None
            reachable = is_leak(parsed, st) if parsed is not None else (st == 200 and text.strip() != "")
            if not reachable:
                not_reachable.append(p)
        check("A4-3", not not_reachable,
              f"登记为匿名的端点必须仍能匿名访问（否则 Nginx 探活会被摘流）；被误拦={not_reachable}")
        check("A4-4", len(leaks) <= len(ALWAYS_ANONYMOUS),
              f"漏出总数 {len(leaks)} 不得超过登记数 {len(ALWAYS_ANONYMOUS)}")

        failed = sum(1 for _, ok in results if not ok)
        print()
        log("SUMMARY", f"{len(results) - failed}/{len(results)} PASS")
        return 1 if failed else 0
    finally:
        stop_server(proc, fh)
        left = subprocess.run("lsof -Pan -i:8080 -sTCP:LISTEN", shell=True,
                              capture_output=True, text=True).stdout.strip()
        log("PORT", f"8080 LISTEN 残留：{left or '无'}")


if __name__ == "__main__":
    sys.exit(main())
