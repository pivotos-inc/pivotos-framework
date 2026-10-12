#!/usr/bin/env python3
"""V3.1-D · C3-1 字段加密密钥守卫的进程级实证（自起自停 fat jar，只 kill 本轮 PID）。

单测（FieldEncryptKeyGuardTest）只能证明「判定函数」对；这里证明的是**进程真的起不来/起得来**：
  G-1 prod 形态未注入 PIVOTOS_FIELD_ENCRYPT_KEY → 进程非零退出，日志给出可执行提示
      （防「守卫写了但没接进启动链路」——这是只写工具类最容易出现的假绿）
  G-2 prod 形态注入 32 位自定义密钥 → 正常起服且探活可用
      （防反向假绿：守卫写成「prod 一律拒启动」也会让 G-1 通过）
  G-3 dev 形态未注入 → 仍正常起服，只有 WARN，没有拒绝
      （C3-1 的硬要求：dev/test 零行为变化，否则 8 个测试库上的全量 IT 会起不来）

用法：
    python3 scripts/e2e/prod_field_encrypt_guard_test.py
前置：8080 空闲；MySQL/Redis 凭据从 application-dev.yml 运行时现读（脚本内不落任何口令）。
"""
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAR = os.path.join(ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
DEV_YML = os.path.join(ROOT, "pivotos-admin-server", "src", "main", "resources", "application-dev.yml")
WORK_ROOT = "/tmp/pivotos-v31d-keyguard"

CUSTOM_KEY = "abcdefghijklmnopqrstuvwxyz012345"  # 32 位占位，非真凭据

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


def port_busy(port=8080) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def live_ok() -> bool:
    try:
        with urllib.request.urlopen(BASE + "/health/live", timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False


def dev_db_env():
    text = open(DEV_YML, encoding="utf-8").read()

    def pick(pattern):
        m = re.search(pattern, text)
        return m.group(1).strip('"') if m else ""

    return {
        "PIVOTOS_MYSQL_URL": pick(r"url:\s*\$\{PIVOTOS_MYSQL_URL:([^}]+)\}"),
        "PIVOTOS_MYSQL_USERNAME": pick(r"username:\s*\$\{PIVOTOS_MYSQL_USERNAME:([^}]+)\}"),
        "PIVOTOS_MYSQL_PASSWORD": pick(r"password:\s*\$\{PIVOTOS_MYSQL_PASSWORD:([^}]+)\}"),
        "PIVOTOS_REDIS_HOST": pick(r"host:\s*\$\{PIVOTOS_REDIS_HOST:([^}]+)\}"),
        "PIVOTOS_REDIS_PASSWORD": pick(r"password:\s*\$\{PIVOTOS_REDIS_PASSWORD:([^}]+)\}"),
    }


def base_env(profile, extra=None, tag=""):
    """prod 形态的 A1 出口必须撑满（缺即 fail-fast），否则验的就不是密钥守卫了"""
    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    # 每个场景一个日志目录：prod 形态应用日志落滚动文件，共用目录会让上一个场景的失败日志
    # 污染下一个场景的断言（本轮实踩：G-2 读到了 G-1 的「启动校验失败」）
    env["PIVOTOS_LOG_DIR"] = os.path.join(WORK_ROOT, f"logs-{profile}-{tag}")
    if profile == "prod":
        env.update(dev_db_env())
        env.update({
            "PIVOTOS_COS_ACCESS_KEY": "v31d-e2e-placeholder",
            "PIVOTOS_COS_SECRET_KEY": "v31d-e2e-placeholder",
            "PIVOTOS_LLM_API_KEY": "v31d-e2e-placeholder",
            "PIVOTOS_XXL_JOB_ACCESS_TOKEN": "v31d-e2e-placeholder",
            "PIVOTOS_SEARCH_TYPE": "simple",
        })
    if extra:
        env.update(extra)
    return env


def launch(profile, extra=None, log_name="run.log", tag=""):
    cwd = os.path.join(WORK_ROOT, f"cwd-{profile}")
    os.makedirs(cwd, exist_ok=True)
    log_path = os.path.join(WORK_ROOT, log_name)
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, f"--spring.profiles.active={profile}"]
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT,
                            env=base_env(profile, extra, tag), cwd=cwd)
    return proc, fh, log_path


def stop(proc, fh):
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


def read_logs(log_path, profile, tag=""):
    """控制台 + 滚动文件一起读：prod/test 形态应用日志只落滚动文件，控制台只有 JVM 输出，
    只看控制台会把「启动校验失败」这类归因漏掉（本轮实踩）。"""
    chunks = []
    for path in [log_path, os.path.join(WORK_ROOT, f"logs-{profile}-{tag}")]:
        if os.path.isdir(path):
            for name in sorted(os.listdir(path)):
                full = os.path.join(path, name)
                if os.path.isfile(full):
                    chunks.append(open(full, encoding="utf-8", errors="ignore").read())
        elif os.path.isfile(path):
            chunks.append(open(path, encoding="utf-8", errors="ignore").read())
    return "\n".join(chunks)


def wait_ready(proc, seconds=240):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if proc.poll() is not None:
            return False
        if live_ok():
            return True
        time.sleep(2)
    return False


def scenario_prod_without_key():
    log("SCENARIO", "G-1 prod 未注入 PIVOTOS_FIELD_ENCRYPT_KEY → 必须拒启动")
    proc, fh, log_path = launch("prod", log_name="prod-nokey.log", tag="nokey")
    try:
        deadline = time.time() + 180
        started = False
        exit_code = None
        while time.time() < deadline:
            if proc.poll() is not None:
                exit_code = proc.returncode
                break
            if live_ok():
                started = True
                break
            time.sleep(2)
        if started:
            stop(proc, fh)
        text = read_logs(log_path, "prod", "nokey")
        check("G-1a", not started, "prod 未注入密钥时不得起服（起服即守卫失效）")
        check("G-1b", exit_code not in (None, 0), f"进程必须非零退出，实测 exit={exit_code}")
        check("G-1c", "字段加密密钥校验失败" in text, "日志必须给出「字段加密密钥校验失败」的明确归因")
        check("G-1d", "PIVOTOS_FIELD_ENCRYPT_KEY" in text, "日志必须告诉运维该注入哪个环境变量")
    finally:
        stop(proc, fh)


def scenario_prod_with_key():
    log("SCENARIO", "G-2 prod 注入 32 位自定义密钥 → 必须正常起服（防「prod 一律拒启动」的反向假绿）")
    proc, fh, log_path = launch("prod", {"PIVOTOS_FIELD_ENCRYPT_KEY": CUSTOM_KEY},
                                log_name="prod-withkey.log", tag="withkey")
    try:
        ready = wait_ready(proc)
        text = read_logs(log_path, "prod", "withkey")
        check("G-2a", ready, "注入自定义密钥后 prod 必须能起服")
        check("G-2b", "字段加密密钥校验失败" not in text, "不得再出现密钥校验失败")
        if ready:
            with urllib.request.urlopen(BASE + "/health/live", timeout=5) as resp:
                body = json.loads(resp.read())
            check("G-2c", body.get("status") == "UP", f"探活仍可用：{body}")
    finally:
        stop(proc, fh)


def scenario_dev_without_key():
    log("SCENARIO", "G-3 dev 未注入 → 正常起服且只有 WARN（dev/test 零行为变化是硬要求）")
    proc, fh, log_path = launch("dev", log_name="dev-nokey.log", tag="nokey")
    try:
        ready = wait_ready(proc)
        text = read_logs(log_path, "dev", "nokey")
        check("G-3a", ready, "dev 形态未注入密钥必须仍能起服")
        check("G-3b", "字段加密密钥校验失败" not in text, "dev 不得因密钥默认值而被拒启动")
        check("G-3c", "当前使用开发默认密钥" in text, "dev 必须打印 WARN（零信号等于没接）")
    finally:
        stop(proc, fh)
    # dev 形态不得落应用滚动日志（C4-1 只把 test 纳入）
    generated = [p for p in os.listdir(os.path.join(WORK_ROOT, "logs-dev-nokey"))
                 if p.startswith("pivotos-admin-server")] \
        if os.path.isdir(os.path.join(WORK_ROOT, "logs-dev-nokey")) else []
    check("G-3d", not generated, f"dev 形态不落应用滚动日志：{generated}")


def main():
    if os.path.exists(WORK_ROOT):
        shutil.rmtree(WORK_ROOT)
    os.makedirs(WORK_ROOT, exist_ok=True)
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。先确认归属："
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")

    for scenario in (scenario_prod_without_key, scenario_prod_with_key, scenario_dev_without_key):
        scenario()

    passed = sum(1 for _, ok in results if ok)
    print(f"\n==== 字段加密密钥守卫实证：{passed}/{len(results)} PASS ====")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
