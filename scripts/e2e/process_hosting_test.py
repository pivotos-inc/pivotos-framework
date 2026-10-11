#!/usr/bin/env python3
"""V3.1-G · P1-B3 进程托管 + P1-B2 连接池生产参数 E2E（自起自停 fat jar，只 kill 本轮 PID）。

五个场景，逐步收口：

  ┃ 场景 1（预检·缺环境变量）：prod profile 缺必需环境变量 → start-prod.sh 拒绝启动，
  ┃                          且不拉起任何 java 进程（这是 A1 凭据外置的配套门禁）
  ┃ 场景 2（预检·端口占用）  ：端口被别的进程占住 → preflight 报出占用者 PID 并拒绝启动
  ┃ 场景 3（预检·通过）      ：环境变量齐全 + 端口空闲 + 库可达 → preflight 退出 0；
  ┃                          库连不上时**只告警不阻断**（--strict 才阻断）
  ┃ 场景 4（连接池·参数落地）：经 start-prod.sh 起服后，/actuator/prometheus 上
  ┃                          pivotos_datasource_connections_max{pool="master"}
  ┃                          dev == 20、prod == 50 —— 证明「yml → DruidConfig → 运行时池」
  ┃                          这条链路真的通了，而不是被静默忽略；
  ┃                          同时断言 hikaricp.* 一条都没有（本项目是 Druid，承接 V3.1-F M-11）
  ┃ 场景 5（优雅停机）      ：SIGTERM 走 stop-prod.sh → 进程自行退出，
  ┃                          且应用日志出现 graceful shutdown（Log.info 级，生产可见）

为什么必须真起进程：
  连接池参数绑不绑得上，只有运行时池的 getMaxActive() 说了算；
  优雅停机走没走 graceful 分支，只有真实 SIGTERM 后的日志说了算。
  这两件事 MockMvc / ApplicationContextRunner 都证明不了。

⚠️ 端口纪律：脚本启动前预检 8080，被占即报错退出，**绝不 kill 他人进程**；
   收口时只 kill 本轮自己记录的 PID（取自本轮 LOG_DIR 下的 pid 文件）。

用法：
    python3 scripts/e2e/process_hosting_test.py
    python3 scripts/e2e/process_hosting_test.py --only=preflight   # 场景 1/2/3
    python3 scripts/e2e/process_hosting_test.py --only=pool       # 场景 4
    python3 scripts/e2e/process_hosting_test.py --only=shutdown # 场景 5
"""
import argparse
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAR = os.path.join(ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
OPS = os.path.join(ROOT, "scripts", "ops")
START_SH = os.path.join(OPS, "start-prod.sh")
STOP_SH = os.path.join(OPS, "stop-prod.sh")
PREFLIGHT = os.path.join(OPS, "preflight.py")
WORK_ROOT = "/tmp/pivotos-v31g-hosting-e2e"

APP_HOME = os.path.join(WORK_ROOT, "app")
PORT = 8080

# prod profile 启动用：指向 **dev** 实例做只读验证（Flyway 仅校验），不写业务数据
PROD_ENV = {
    "PIVOTOS_MYSQL_URL": "jdbc:mysql://175.24.176.176:3306/pivotos_dev?useUnicode=true"
                         "&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false"
                         "&allowMultiQueries=false",
    "PIVOTOS_MYSQL_USERNAME": "root",
    "PIVOTOS_MYSQL_PASSWORD": "mysql_DNCi3f",
    "PIVOTOS_REDIS_HOST": "175.24.176.176",
    "PIVOTOS_REDIS_PASSWORD": "redis_Q6aiiX",
    "PIVOTOS_FIELD_ENCRYPT_KEY": "e2eTestKey123456",
    # COS 凭据**实际是 prod 起服的硬门槛**：FileConfig$StorageStrategyConfiguration
    # 会在刷新期急切装配 S3 客户端，值为空直接 NPE（"Access key ID cannot be blank"）。
    # 这里填非空占位即可——起服只构造客户端，不会真的访问桶。
    "PIVOTOS_COS_ACCESS_KEY": "e2eAccessKeyPlaceh",
    "PIVOTOS_COS_SECRET_KEY": "e2eSecretKeyPlaceholder0123456789",
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
        raise AssertionError(f"{tag} 断言失败：{msg}")


# ────────────────────────── 环境 ──────────────────────────
def clean_env(extra=None):
    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    env["JNA_LIBRARY_PATH"] = "/opt/homebrew/lib"
    if extra:
        env.update(extra)
    return env


def log_dir_for(tag):
    d = os.path.join(WORK_ROOT, "logs", tag)
    os.makedirs(d, exist_ok=True)
    return d


def pid_file(log_dir):
    return os.path.join(log_dir, "admin-server.pid")


def read_pid(log_dir):
    p = pid_file(log_dir)
    if not os.path.isfile(p):
        return None
    try:
        return int(open(p, encoding="utf-8").read().strip())
    except (ValueError, OSError):
        return None


def port_busy(port=PORT):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def http_get(path, timeout=15, headers=None, port=PORT):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def scrape():
    status, headers, raw = http_get("/actuator/prometheus")
    return status, (headers.get("Content-Type") or ""), raw.decode("utf-8", "replace")


def metric_value(text, name, tag_filter=None):
    pat = re.compile(r"^" + re.escape(name) + r"(\{([^}]*)\})?\s+([0-9eE+.\-]+)$")
    for line in text.splitlines():
        m = pat.match(line.strip())
        if not m:
            continue
        tags = m.group(2) or ""
        if tag_filter and tag_filter not in tags:
            continue
        return float(m.group(3))
    return None


# ────────────────────────── 场景 ──────────────────────────
def prepare_app_home():
    os.makedirs(APP_HOME, exist_ok=True)
    link = os.path.join(APP_HOME, "pivotos-admin-server.jar")
    if not os.path.exists(link):
        os.symlink(JAR, link)
    for name in ("run.sh", "start-prod.sh", "stop-prod.sh", "preflight.py", "jvm-opts.sh"):
        src, dst = os.path.join(OPS, name), os.path.join(APP_HOME, name)
        if os.path.isfile(src) and not os.path.isfile(dst):
            shutil.copy2(src, dst)
            os.chmod(dst, 0o755)


def scenario_preflight_missing_env():
    log("S1", "预检·缺必需环境变量 → 必须拒绝启动且不拉起进程")
    before = read_pid(log_dir_for("missing-env"))
    env = clean_env({
        "APP_HOME": APP_HOME,
        "PIVOTOS_PROFILE": "prod",
        "PIVOTOS_LOG_DIR": log_dir_for("missing-env"),
        "SERVER_PORT": str(PORT),
    })
    # 故意不注入任何 PIVOTOS_* 凭据
    proc = subprocess.run(["bash", START_SH], env=env, capture_output=True, encoding="utf-8", errors="replace", timeout=300)
    out = proc.stdout + proc.stderr
    check("S1-1", proc.returncode != 0, f"start-prod.sh 拒绝启动（exit={proc.returncode}）")
    # 拦截发生在 preflight.py（早于脚本自带的兜底检查），故断言预检的提示：
    # 既要说清是哪一类问题，也要列出**具体变量名**，否则运维还得去翻代码
    check("S1-2", "必需环境变量" in out, "报错信息点明「必需环境变量」缺失")
    check("S1-2b", "PIVOTOS_MYSQL_URL" in out, "报错信息列出具体缺失变量名 PIVOTOS_MYSQL_URL")
    check("S1-2c", "PIVOTOS_FIELD_ENCRYPT_KEY" in out, "报错信息列出具体缺失变量名 PIVOTOS_FIELD_ENCRYPT_KEY")
    check("S1-3", not port_busy(), "8080 未被本轮拉起（无残留进程）")
    check("S1-4", read_pid(log_dir_for("missing-env")) == before, "未生成新的 PID 文件")


def scenario_preflight_port_busy():
    log("S2", "预检·端口被占用 → 必须报出占用者并拒绝")
    holder = subprocess.Popen(
        [sys.executable, "-c",
         "import socket,time\n"
         "s=socket.socket()\n"
         "s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)\n"
         f"s.bind(('0.0.0.0',{PORT}))\n"
         "s.listen(5)\n"
         "time.sleep(180)\n"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(2)
        check("S2-0", port_busy(), "占位进程已监听 8080")
        env = clean_env({"PIVOTOS_PROFILE": "dev", "SERVER_PORT": str(PORT)})
        proc = subprocess.run([sys.executable, PREFLIGHT, "--profile=dev", f"--port={PORT}", "--skip-db"],
                         env=env, capture_output=True, encoding="utf-8", errors="replace", timeout=120)
        out = proc.stdout + proc.stderr
        check("S2-1", proc.returncode != 0, f"preflight 拒绝（exit={proc.returncode}）")
        check("S2-2", "已被占用" in out, "输出含「已被占用」")
        check("S2-3", re.search(r"\b\d{2,7}\b", out.split("已被占用：")[-1]) is not None,
              "输出含占用者 PID（供人工判定归属，不自动 kill）")
    finally:
        holder.terminate()
        try:
            holder.wait(timeout=10)
        except subprocess.TimeoutExpired:
            holder.kill()
        for _ in range(30):
            if not port_busy():
                break
            time.sleep(1)


def scenario_preflight_ok():
    log("S3", "预检·条件满足 → 退出 0，且库连通为 PASS")
    env = clean_env({
        "PIVOTOS_PROFILE": "dev",
        "SERVER_PORT": str(PORT),
        "PIVOTOS_MYSQL_URL": "jdbc:mysql://175.24.176.176:3306/pivotos_dev?useSSL=false",
        "PIVOTOS_MYSQL_USERNAME": "root",
        "PIVOTOS_MYSQL_PASSWORD": "mysql_DNCi3f",
    })
    proc = subprocess.run([sys.executable, PREFLIGHT, "--profile=dev", f"--port={PORT}",
                            "--jar", JAR, "--db-timeout", "5"],
                           env=env, capture_output=True, encoding="utf-8", errors="replace", timeout=120)
    out = proc.stdout + proc.stderr
    check("S3-1", proc.returncode == 0, f"preflight 通过（exit={proc.returncode}）")
    check("S3-2", "数据源连通：175.24.176.176:3306/pivotos_dev 可连通" in out,
          "数据源连通判为 PASS")


def boot_via_start_script(profile, tag, timeout=300):
    """用真实的 start-prod.sh 起服（会先跑 preflight），返回 (proc, log_dir, env)。"""
    log_dir = log_dir_for(tag)
    env = clean_env({
        "APP_HOME": APP_HOME,
        "PIVOTOS_PROFILE": profile,
        "PIVOTOS_LOG_DIR": log_dir,
        "SERVER_PORT": str(PORT),
    })
    if profile == "prod":
        env.update(PROD_ENV)
    proc = subprocess.run(["bash", START_SH], env=env, capture_output=True,
                          encoding="utf-8", errors="replace", timeout=timeout)
    return proc, log_dir, env


def stop_via_stop_script(log_dir, env):
    proc = subprocess.run(["bash", STOP_SH], env=env, capture_output=True,
                          encoding="utf-8", errors="replace", timeout=120)
    return proc


def assert_pool_max(text, expected, tag):
    value = metric_value(text, "pivotos_datasource_connections_max", 'pool="master"')
    check(tag, value == expected,
          f"pivotos_datasource_connections_max{{pool=\"master\"}} == {expected}（实际 {value}）")


def scenario_pool_config():
    log("S4", "连接池·参数落地：dev=20 / prod=50，且 hikaricp.* 不存在")
    for profile, expected in (("dev", 20.0), ("prod", 50.0)):
        tag = f"pool-{profile}"
        proc, log_dir, env = boot_via_start_script(profile, tag)
        try:
            check(f"S4-{profile}-boot", proc.returncode == 0,
                  f"start-prod.sh 起服成功（profile={profile}）")
            time.sleep(3)
            status, _, text = scrape()
            check(f"S4-{profile}-scrape", status == 200, f"/actuator/prometheus 可抓（{status}）")
            assert_pool_max(text, expected, f"S4-{profile}-max")
            check(f"S4-{profile}-nohikari", "hikaricp" not in text.lower(),
                  "指标中不含 hikaricp.*（本项目连接池是 Druid）")
        finally:
            stop_via_stop_script(log_dir, env)


def scenario_graceful_shutdown():
    log("S5", "优雅停机：SIGTERM 走 stop-prod.sh → 进程自行退出且日志出现 graceful shutdown")
    proc, log_dir, env = boot_via_start_script("prod", "shutdown")
    try:
        check("S5-1", proc.returncode == 0, "起服成功")
        pid = read_pid(log_dir)
        check("S5-2", pid is not None, f"PID 文件已生成（PID={pid}）")
        stop = stop_via_stop_script(log_dir, env)
        check("S5-3", stop.returncode == 0, f"stop-prod.sh 正常返回（exit={stop.returncode}）")
        check("S5-4", read_pid(log_dir) is None, "PID 文件已清理（进程确实退出）")
        check("S5-5", not port_busy(), "8080 已释放")

        blob = ""
        for root, _, files in os.walk(log_dir):
            for name in files:
                if name.endswith((".log", ".gz")):
                    try:
                        blob += open(os.path.join(root, name), "rb").read().decode(
                            "utf-8", "replace")
                    except OSError:
                        pass
        check("S5-6", "graceful shutdown" in blob.lower(),
              "日志出现 graceful shutdown（证明走了优雅停机分支而非立即 SIGKILL）")
    finally:
        if read_pid(log_dir) is not None:
            p = read_pid(log_dir)
            try:
                os.kill(p, signal.SIGKILL)
            except OSError:
                pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", help="preflight | pool | shutdown")
    args = ap.parse_args()

    if not os.path.isfile(JAR):
        print(f"❌ 找不到 fat jar：{JAR}", file=sys.stderr)
        return 2

    if port_busy():
        print("❌ 8080 已被占用（可能是你自有的进程）。"
              "请先 lsof -Pan -i:8080 -sTCP:LISTEN 确认归属。", file=sys.stderr)
        return 2

    shutil.rmtree(WORK_ROOT, ignore_errors=True)
    os.makedirs(WORK_ROOT, exist_ok=True)
    prepare_app_home()

    only = args.only
    try:
        if only in ("", "preflight"):
            scenario_preflight_missing_env()
            scenario_preflight_port_busy()
            scenario_preflight_ok()
        if only in ("", "pool"):
            scenario_pool_config()
        if only in ("", "shutdown"):
            scenario_graceful_shutdown()
    except AssertionError as e:
        print(f"\n❌ {e}")
        passed = sum(1 for _, ok in results if ok)
        print(f"合计：PASS {passed} · FAIL {len(results) - passed}")
        return 1

    passed = sum(1 for _, ok in results if ok)
    print("-" * 78)
    print(f"合计：PASS {passed} · FAIL {len(results) - passed}")
    print("✅ V3.1-G 进程托管 / 连接池 E2E 全部通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
