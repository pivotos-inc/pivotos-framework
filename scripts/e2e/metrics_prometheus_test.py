#!/usr/bin/env python3
"""V3.1-F · P1-B8-1 指标基础设施 E2E（自起自停 fat jar，只 kill 本轮 PID）。

四个场景，逐步收口：
  ┃ 场景 1（dev·默认）：/actuator/prometheus 匿名可抓、Content-Type 是 Prometheus 文本格式，
  ┃                    并实打覆盖 B8 要求的四类：JVM / HTTP / 连接池 / 检索
  ┃ 场景 2（dev·慢接口）：阈值压到 1ms 后真实请求被计为慢请求，且 tag 是 URI 模板而非实际 URI
  ┃ 场景 3（dev·总开关）：PIVOTOS_METRICS_ENABLED=false → 端点不注册（200 + code=1004），业务照常
  ┃ 场景 4（dev·只关端点）：PIVOTOS_METRICS_PROMETHEUS_ENABLED=false → 只摘端点，业务照常

为什么必须真起进程而不是只跑单测：
  ① 连接池到底是 HikariCP 还是 Druid，只有启动日志说了算——本项目是 **Druid**，
     而 Boot 自带的连接池指标只认 Hikari，于是 hikaricp.* 一条都没有且不报错，
     表现就是「接了指标但连接池那一栏永远空着」。本脚本把这条事实钉成断言。
  ② http.server.requests 是**懒注册**的：没有任何请求时它就是不存在的，
     单测（MockMvc / ApplicationContextRunner）证明不了「真实 Tomcat 下确实出得来」。

用法：
    python3 scripts/e2e/metrics_prometheus_test.py                  # 全跑
    python3 scripts/e2e/metrics_prometheus_test.py --only=default   # 只跑场景 1
    python3 scripts/e2e/metrics_prometheus_test.py --only=slow      # 只跑场景 2
    python3 scripts/e2e/metrics_prometheus_test.py --only=disabled  # 只跑场景 3/4
"""
import argparse
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAR = os.path.join(ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
WORK_ROOT = "/tmp/pivotos-v31f-metrics-e2e"

PROM_PATH = "/actuator/prometheus"

results = []


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(tag, cond, msg):
    results.append((tag, bool(cond)))
    if cond:
        log("PASS", f"{tag} {msg}")
    else:
        log("FAIL", f"{tag} {msg}")
        raise AssertionError(f"{tag} 断言失败：{msg}")


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


def start_server(env_extra=None, timeout=240):
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。"
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    tag = env_extra.get("E2E_TAG", "default") if env_extra else "default"
    log_path = os.path.join(WORK_ROOT, f"{tag}.log")
    cwd = os.path.join(WORK_ROOT, f"cwd-{tag}")
    os.makedirs(cwd, exist_ok=True)
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev"]
    log("START", f"起服（{tag}）日志={log_path}")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT,
                            env=clean_env(env_extra), cwd=cwd)
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

def http_get(path, headers=None, timeout=15):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def get_json(path, headers=None):
    status, _, raw = http_get(path, headers)
    try:
        return status, json.loads(raw)
    except Exception:
        return status, None


def post_json(path, payload, headers=None):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode(),
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


def scrape():
    """抓取一次指标文本；返回 (status, content_type, text)"""
    status, headers, raw = http_get(PROM_PATH)
    return status, (headers.get("Content-Type") or ""), raw.decode("utf-8", "replace")


def present(text, name):
    """指标是否出现（按 Prometheus 的下划线名，行首匹配）"""
    return any(line.startswith(name) for line in text.splitlines())


def first_line(text, name):
    for line in text.splitlines():
        if line.startswith(name):
            return line
    return ""


# ────────────────────────── 场景 ──────────────────────────

def scenario_default():
    """场景 1：默认形态的覆盖与格式"""
    log("SCENARIO", "1/4 dev 默认形态：端点格式 + JVM/HTTP/连接池/检索四类覆盖")
    proc, fh = start_server({"E2E_TAG": "default"})
    try:
        # ① 匿名可抓（Prometheus server 不带登录态）
        status, ctype, text = scrape()
        check("M-1", status == 200, f"{PROM_PATH} 匿名 HTTP {status}")
        check("M-2", "text/plain" in ctype and "0.0.4" in ctype,
              f"Content-Type 必须是 Prometheus 文本格式，实际={ctype}")

        # ② JVM（B8 要求）
        check("M-3", present(text, "jvm_memory_used_bytes"), "JVM 内存指标存在")
        check("M-4", present(text, "jvm_threads_live_threads"), "JVM 线程指标存在")

        # ③ 系统 / 进程
        check("M-5", present(text, "system_cpu_usage") or present(text, "process_cpu_usage"),
              "系统或进程 CPU 指标存在")
        check("M-6", present(text, "logback_events_total"), "日志事件计数存在")

        # ④ HTTP：懒注册，必须有真实请求后才出现（本脚本自己造请求）
        login_token()
        status, _, text = scrape()
        check("M-7", present(text, "http_server_requests_seconds_count"),
              "HTTP 请求直方图存在（真实请求后懒注册）")
        check("M-8", 'uri="/system/auth/login"' in text,
              "HTTP 指标里能看到刚打过的登录请求")

        # ⑤ 连接池：本项目实际是 Druid，Boot 自带的 hikaricp.* 覆盖不到，故自研 binder
        check("M-9", present(text, "pivotos_datasource_connections_active"),
              "Druid 连接池活跃连接指标存在（自研 binder）")
        check("M-10", present(text, "pivotos_datasource_connections_max"),
              "Druid 连接池上限指标存在")
        check("M-11", not present(text, "hikaricp_connections"),
              "hikaricp.* 不存在——本项目连接池是 Druid，这条断言就是该事实的钉子")

        # ⑥ 检索（启动期 KB 索引回灌会触发）
        check("M-12", present(text, "pivotos_search_calls_total") or present(text, "pivotos_search_latency_seconds"),
              "检索调用量/耗时指标存在")

        # ⑦ 公共 tag
        check("M-13", 'application="pivotos-admin-server"' in text, "application 公共 tag 已打上")
    finally:
        stop_server(proc, fh)


def scenario_slow():
    """场景 2：慢接口统计，且 tag 用 URI 模板而不是实际 URI"""
    log("SCENARIO", "2/4 dev 阈值压到 0：慢请求计数与 tag 口径")
    # 阈值单位是**整数毫秒**：耗时按毫秒取整后 >= 阈值才算慢，故阈值取 1 仍会漏掉亚毫秒请求
    # （实测：阈值=1 时 /system/user/1 耗时 0.x ms，取整为 0，一条都不计）——要「全部计入」就写 0。
    proc, fh = start_server({"E2E_TAG": "slow",
                             "PIVOTOS_METRICS_SLOW_REQUEST_THRESHOLD_MS": "0"})
    try:
        token = login_token()
        # 打一个带路径变量的接口：若 tag 取错了（用实际 URI），这里会看到 /system/user/1
        http_get("/system/user/1", headers={"Authorization": token})
        status, _, text = scrape()
        # Micrometer 的点分名 pivotos.http.slow 在 Prometheus 侧是 pivotos_http_slow_total
        # （counter 名 + Prometheus 的 _total 后缀），别按 Java 侧的名字去抓
        check("S-1", present(text, "pivotos_http_slow_total"),
              "慢请求计数存在（阈值 0 = 全部计入）")
        line = first_line(text, "pivotos_http_slow_total")
        check("S-2", 'pattern="/system/user/{id}"' in text,
              f"tag 必须是 URI 模板而不是实际 URI，实际样本={line[:160]}")
        check("S-3", not any('/system/user/1"' in l for l in text.splitlines()
                             if l.startswith("pivotos_http_slow")),
              "绝不能出现含 id 的实际 URI 当 tag（高基数会把 Prometheus 打挂）")
    finally:
        stop_server(proc, fh)


def scenario_disabled():
    """场景 3/4：两个开关的真实后果"""
    log("SCENARIO", "3/4 dev 总开关关闭：端点不注册，业务照常")
    proc, fh = start_server({"E2E_TAG": "disabled", "PIVOTOS_METRICS_ENABLED": "false"})
    try:
        status, body = get_json(PROM_PATH)
        # 端点未注册 → 走全局异常处理：HTTP 200 + code=1004（本项目既有语义，勿按状态码断言）
        check("D-1", status == 200 and body is not None and body.get("code") == 1004,
              f"总开关关闭后端点不注册（200 + code={body.get('code') if body else '?'}）")
        status, body = post_json("/system/auth/login", {"username": "admin", "password": "admin123"})
        check("D-2", status == 200 and body.get("code") == 0, "关掉指标后业务照常（登录 code=0）")
    finally:
        stop_server(proc, fh)

    log("SCENARIO", "4/4 dev 只关端点：只摘端点，业务照常")
    proc, fh = start_server({"E2E_TAG": "endpoint-off", "PIVOTOS_METRICS_PROMETHEUS_ENABLED": "false"})
    try:
        status, body = get_json(PROM_PATH)
        check("D-3", status == 200 and body is not None and body.get("code") == 1004,
              f"只关端点时端点不注册（200 + code={body.get('code') if body else '?'}）")
        status, body = post_json("/system/auth/login", {"username": "admin", "password": "admin123"})
        check("D-4", status == 200 and body.get("code") == 0, "只关端点不影响业务（登录 code=0）")
    finally:
        stop_server(proc, fh)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", choices=["default", "slow", "disabled", "all"], default="all")
    args = parser.parse_args()

    if os.path.exists(WORK_ROOT):
        shutil.rmtree(WORK_ROOT)
    os.makedirs(WORK_ROOT, exist_ok=True)

    scenarios = {
        "default": scenario_default,
        "slow": scenario_slow,
        "disabled": scenario_disabled,
    }
    todo = list(scenarios.values()) if args.only == "all" else [scenarios[args.only]]

    failed = 0
    try:
        for scenario in todo:
            try:
                scenario()
            except AssertionError as e:
                log("ERROR", str(e))
                failed += 1
            except Exception as e:  # noqa: BLE001
                log("ERROR", f"{scenario.__name__} 异常：{e}")
                failed += 1
    finally:
        total = len(results)
        passed = sum(1 for _, ok in results if ok)
        log("SUMMARY", f"{passed}/{total} 通过，失败场景 {failed} 个")
        if os.path.exists(WORK_ROOT) and not os.environ.get("KEEP_LOGS"):
            shutil.rmtree(WORK_ROOT, ignore_errors=True)
        if port_busy():
            log("WARN", "8080 仍被占用，请人工确认归属（本脚本只 kill 自己拉起的进程）")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
