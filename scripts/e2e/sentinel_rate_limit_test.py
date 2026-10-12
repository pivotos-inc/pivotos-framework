#!/usr/bin/env python3
"""V3.2-A · Sentinel 真机限流复跑 E2E（自起自停 fat jar，只 kill 本轮 PID）。

把 V3-S2 L3 那次「手工 curl 打 429」固化为可复跑资产 —— 它此前没有脚本，
所以既无法回归、也没法证明「resourcePrefix 那次修复现在还生效」。

四个场景，逐步收口：
  ┃ 场景 1（正向）：规则名写成 URI 原样 `/system/auth/login`（QPS=1）→ 连打必出 429，
  ┃                  body 含 "Blocked by Sentinel"，2s 后自行恢复
  ┃ 场景 2（负向·历史坑复现）：规则名写成带前缀的 `pivotos:/system/auth/login`
  ┃                  → Guard 因其不以 `/` 开头而再拼一次前缀，资源名变成
  ┃                    `pivotos:pivotos:/system/auth/login`，与 WebMvc 拦截器用的 URI 资源名
  ┃                    对不上 → **连打全 200，限流静默失效**。这正是当年事故的原样复现。
  ┃ 场景 3（负向·规则不匹配）：规则指向根本不存在的 URI → 全 200，
  ┃                  证明「出 429」是规则匹配驱动的，而不是别的原因（如登录接口自带频控）
  ┃ 场景 4（负向·开关）：sentinel-enabled=false → 全 200，且日志不再出现「Sentinel 保护已启用」

为什么必须真起进程而不是只跑单测：
  ① SentinelGuardTest 只有 4 条纯计算断言，证明不了 **SCA 的 SentinelWebInterceptor 真的
     在 Tomcat 里拦到了 `/system/auth/login`** —— Guard 只负责往 FlowRuleManager 塞规则，
     真正拦请求的是 SCA；「规则加载了」和「请求被拦了」是两件事，历史上就是在这里脱节的。
  ② 本项目 HTTP 状态码恒 200（GlobalExceptionHandler 把异常转成 R.fail + ResponseEntity.ok），
     而 Sentinel 的 BlockExceptionHandler 在 Filter 层直接返回 **429**，不进 MVC ——
     断言 429 因此是一条**有区分度**的判据（恒 200 的口径在这里恰好反着来）。

为什么默认把 Nacos 指到 127.0.0.1:9999：
  Sentinel 的限流是**纯本地能力**（规则来自配置，不读 dashboard、不连控制台），
  本脚本要证的是「规则 → 拦截」这一段，与 Nacos 是否可达无关。
  而 Nacos 不可达有两种形态会拖死启动：**连接被拒**时客户端重试 45 次后放行（约 100s），
  **连得上但极慢**时每次 3s 超时、客户端几乎不放弃，启动会一直卡在配置加载阶段
  （2026-10-12 实测：4 分半仍未到 Flyway 校验）。因此默认指不可达端口让它快速拒绝，
  需要连真机 Nacos 时用 --nacos-addr 显式指定（会显著拉长起服时间，起服超时已放宽到 420s）。

用法：
    python3 scripts/e2e/sentinel_rate_limit_test.py                    # 全跑（4 场景）
    python3 scripts/e2e/sentinel_rate_limit_test.py --only=block       # 只跑场景 1
    python3 scripts/e2e/sentinel_rate_limit_test.py --only=prefix      # 只跑场景 2（负向）
    python3 scripts/e2e/sentinel_rate_limit_test.py --only=mismatch    # 只跑场景 3（负向）
    python3 scripts/e2e/sentinel_rate_limit_test.py --only=disabled    # 只跑场景 4（负向）
    python3 scripts/e2e/sentinel_rate_limit_test.py --nacos-addr 175.24.176.176:8848
"""
import argparse
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

BASE = "http://127.0.0.1:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAR = os.path.join(ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
WORK_ROOT = "/tmp/pivotos-v32a-sentinel-e2e"

LOGIN_PATH = "/system/auth/login"
BLOCK_MARK = "Blocked by Sentinel"

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


# ────────────────────────── 前置校验 ──────────────────────────

def assert_alibaba_jar():
    """默认形态 fat jar 里没有 sentinel/nacos，拿它跑本脚本会「全 200 + 看不出为什么」。

    这就是本脚本唯一的形态守卫：宁可开工前报错，也不要跑完拿到一堆假绿。
    """
    if not os.path.exists(JAR):
        raise RuntimeError(f"找不到 fat jar：{JAR}（先 mvn clean package）")
    with zipfile.ZipFile(JAR) as z:
        names = z.namelist()
    sentinel = [n for n in names if "sentinel" in n.lower()]
    nacos = [n for n in names if "nacos" in n.lower()]
    if not sentinel or not nacos:
        raise RuntimeError(
            "fat jar 里没有 sentinel/nacos 构件（是默认形态，不是 -P alibaba 形态）。\n"
            "  本脚本必须跑在 alibaba 形态上：mvn clean package -P alibaba -DskipTests\n"
            "  ⚠️ 换 profile 重打包前先删旧 fat jar，否则 repackage 不会替换旧包（V3-S2 教训①）。")


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


def start_server(tag, cli_args, env_extra=None, timeout=420):
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。"
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    log_path = os.path.join(WORK_ROOT, f"{tag}.log")
    cwd = os.path.join(WORK_ROOT, f"cwd-{tag}")
    os.makedirs(cwd, exist_ok=True)
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev"] + list(cli_args)
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

def http_get(path, timeout=15):
    req = urllib.request.Request(BASE + path)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def hit_login(timeout=15):
    """打一次登录接口（限流在 Filter 层，body 合法即可，不关心业务结果）。"""
    payload = b'{"username":"admin","password":"admin123"}'
    req = urllib.request.Request(BASE + LOGIN_PATH, data=payload,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def burst(n=6):
    """连打 n 次（无间隔），返回 [(status, body)]。"""
    return [hit_login() for _ in range(n)]


def read_log(path):
    with open(path, "rb") as f:
        return f.read().decode("utf-8", "replace")


# ────────────────────────── 场景 ──────────────────────────

def base_args(nacos_addr):
    return [
        f"--spring.cloud.nacos.config.server-addr={nacos_addr}",
        f"--spring.cloud.nacos.discovery.server-addr={nacos_addr}",
    ]


def scenario_block(nacos_addr):
    """场景 1（正向）：URI 原样 → 连打出 429 → 2s 后恢复。"""
    tag = "block"
    args = base_args(nacos_addr) + [
        "--pivotos.cloud.alibaba.sentinel-enabled=true",
        "--pivotos.cloud.alibaba.rules[/system/auth/login]=1",
    ]
    proc, fh, log_path = start_server(tag, args, {"PIVOTOS_CLOUD_ENABLED": "true",
                                                  "PIVOTOS_CLOUD_PROVIDER": "alibaba"})
    try:
        text = read_log(log_path)
        check("S1-1", "[CLOUD][alibaba] Sentinel 保护已启用" in text,
              "起服日志出现「Sentinel 保护已启用」（证明 Guard 装配了）")
        check("S1-2", "Sentinel 流控规则：/system/auth/login -> QPS 1" in text,
              "日志打印的实际资源名就是 URI 原样（前缀坑未复发）")
        check("S1-3", "pivotos:/system/auth/login" not in text,
              "日志里不存在被拼前缀的资源名")

        hits = burst(6)
        codes = [h[0] for h in hits]
        blocked = [h for h in hits if h[0] == 429]
        passed = [h for h in hits if h[0] == 200]
        log("INFO", f"连打 6 次状态码={codes}")

        check("S1-4", len(blocked) >= 1, f"连打 6 次至少 1 次被限流（实际 {len(blocked)} 次 429）")
        check("S1-5", len(passed) >= 1, f"QPS=1 下仍有请求放行（实际 {len(passed)} 次 200）")
        check("S1-6", all(BLOCK_MARK in b[1] for b in blocked),
              f"429 响应体均含 \"{BLOCK_MARK}\"（Sentinel BlockExceptionHandler 生效）")
        check("S1-7", all(not b[1].lstrip().startswith("{") for b in blocked),
              "429 不是 MVC 返回的 JSON（证明拦在 Filter 层，未进 Controller）")

        time.sleep(2.5)
        code, body = hit_login()
        check("S1-8", code != 429, f"等待 2.5s 后恢复（状态码={code}，未被继续限流）")
        check("S1-9", code == 200 and '"code":0' in body.replace(" ", ""),
              "恢复后登录业务码 code=0（限流没有把登录接口打坏）")
    finally:
        stop_server(proc, fh)


def scenario_prefix(nacos_addr):
    """场景 2（负向）：规则名带前缀 —— 历史坑的原样复现，期望**全 200**（限流静默失效）。"""
    tag = "prefix"
    args = base_args(nacos_addr) + [
        "--pivotos.cloud.alibaba.sentinel-enabled=true",
        "--pivotos.cloud.alibaba.rules[pivotos:/system/auth/login]=1",
    ]
    proc, fh, log_path = start_server(tag, args, {"PIVOTOS_CLOUD_ENABLED": "true",
                                                  "PIVOTOS_CLOUD_PROVIDER": "alibaba"})
    try:
        text = read_log(log_path)
        check("S2-1", "Sentinel 流控规则：pivotos:pivotos:/system/auth/login -> QPS 1" in text,
              "带前缀的规则名被二次拼前缀（资源名已与 URI 不一致）")

        hits = burst(6)
        codes = [h[0] for h in hits]
        log("INFO", f"连打 6 次状态码={codes}")
        check("S2-2", 429 not in codes,
              f"资源名被拼前缀后**不应**限流（实际状态码={codes}）—— "
              "这条红说明规则意外生效了，需复核 SentinelWebInterceptor 的资源名来源")
        check("S2-3", codes.count(200) == 6, "6 次全部 200（静默失效形态，与历史事故一致）")
    finally:
        stop_server(proc, fh)


def scenario_mismatch(nacos_addr):
    """场景 3（负向）：规则指向不存在的 URI → 全 200，证明 429 由规则匹配驱动。"""
    tag = "mismatch"
    args = base_args(nacos_addr) + [
        "--pivotos.cloud.alibaba.sentinel-enabled=true",
        "--pivotos.cloud.alibaba.rules[/system/auth/no-such-endpoint]=1",
    ]
    proc, fh, log_path = start_server(tag, args, {"PIVOTOS_CLOUD_ENABLED": "true",
                                                  "PIVOTOS_CLOUD_PROVIDER": "alibaba"})
    try:
        text = read_log(log_path)
        check("S3-1", "Sentinel 流控规则：/system/auth/no-such-endpoint -> QPS 1" in text,
              "规则已加载（指向不存在的 URI）")

        hits = burst(6)
        codes = [h[0] for h in hits]
        log("INFO", f"连打 6 次状态码={codes}")
        check("S3-2", 429 not in codes and codes.count(200) == 6,
              f"规则不匹配时**不应**限流（实际={codes}）—— 反证场景 1 的 429 确由规则匹配产生")
    finally:
        stop_server(proc, fh)


def scenario_disabled(nacos_addr):
    """场景 4（负向）：sentinel-enabled=false → 全 200 且 Guard 不装配。"""
    tag = "disabled"
    args = base_args(nacos_addr) + [
        "--pivotos.cloud.alibaba.sentinel-enabled=false",
        "--pivotos.cloud.alibaba.rules[/system/auth/login]=1",
    ]
    proc, fh, log_path = start_server(tag, args, {"PIVOTOS_CLOUD_ENABLED": "true",
                                                  "PIVOTOS_CLOUD_PROVIDER": "alibaba"})
    try:
        text = read_log(log_path)
        check("S4-1", "[CLOUD][alibaba] Sentinel 保护已启用" not in text,
              "开关关闭时 Guard 不装配（日志无「Sentinel 保护已启用」）")

        hits = burst(6)
        codes = [h[0] for h in hits]
        log("INFO", f"连打 6 次状态码={codes}")
        check("S4-2", 429 not in codes and codes.count(200) == 6,
              f"开关关闭时**不应**限流（实际={codes}）")
    finally:
        stop_server(proc, fh)


# ────────────────────────── 主流程 ──────────────────────────

SCENARIOS = {
    "block": scenario_block,
    "prefix": scenario_prefix,
    "mismatch": scenario_mismatch,
    "disabled": scenario_disabled,
}


def main():
    ap = argparse.ArgumentParser(description="Sentinel 真机限流复跑 E2E")
    ap.add_argument("--only", choices=list(SCENARIOS), help="只跑指定场景")
    ap.add_argument("--nacos-addr", default="127.0.0.1:9999",
                    help="Nacos 地址（默认指不可达端口，让连接快速失败；"
                         "指真机会显著拉长起服时间）")
    args = ap.parse_args()

    os.makedirs(WORK_ROOT, exist_ok=True)
    assert_alibaba_jar()
    log("PRE", f"fat jar = {JAR}（{os.path.getsize(JAR)} 字节，形态校验通过：含 sentinel/nacos）")

    plan = [args.only] if args.only else ["block", "prefix", "mismatch", "disabled"]
    failed = []
    for name in plan:
        log("SCENARIO", f"===== {name} =====")
        try:
            SCENARIOS[name](args.nacos_addr)
        except Exception as e:
            failed.append((name, e))
            log("ERROR", f"{name} 场景异常：{e}")

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
