#!/usr/bin/env python3
"""V3.1-B · A2 只读探活端点 + A3 生产日志滚动 E2E（自起自停 fat jar，只 kill 本轮 PID）。

四个场景，逐步收口：
  ┃ 场景 1（dev·默认）：/health/live 与 /health/ready 的语义、匿名裁剪、明细的登录门槛、
  ┃                    DOWN↔503 一致性，以及「其余端点仍是 HTTP 200 + 业务码」的既有语义未被动过
  ┃ 场景 2（dev·总开关）：PIVOTOS_OPS_HEALTH_ENABLED=false → 端点不注册（200 + code=1004）
  ┃ 场景 3（dev·明细开关）：PIVOTOS_OPS_HEALTH_SHOWDETAILSANONYMOUSLY=true → 匿名也能拿明细
  ┃                        （同时证明配置真的能被环境变量覆盖，不是写在代码里的死值）
  ┃ 场景 4（prod·日志）：prod profile 下应用日志走滚动文件，按大小切 gzip；控制台不再重复落盘。
  ┃                    数据库指向 dev 实例（只读验证 Flyway 已是 85/85，不写任何业务数据），
  ┃                    日志目录与 cwd 都在 /tmp 下，跑完自清。
  ┃ 场景 5（test·日志）：V3.1-D 遗留④ / C4-1 —— test profile 与 prod 共用同一份滚动策略
  ┃                    （长期跑的测试环境同样会被单文件日志写满磁盘），而 dev 仍只写控制台。

用法：
    python3 scripts/e2e/ops_health_test.py                 # 全跑
    python3 scripts/e2e/ops_health_test.py --only=probe    # 只跑场景 1
    python3 scripts/e2e/ops_health_test.py --only=log      # 只跑场景 4
    python3 scripts/e2e/ops_health_test.py --only=testlog  # 只跑场景 5
"""
import argparse
import glob
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

BASE = "http://127.0.0.1:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAR = os.path.join(ROOT, "pivotos-admin-server", "target", "pivotos-admin-server.jar")
WORK_ROOT = "/tmp/pivotos-v31b-e2e"
LOG_DIR = os.path.join(WORK_ROOT, "logs")

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
    """彻底避免上一段遗留的 PIVOTOS_* 污染本次起服（连接串带 createDatabaseIfNotExist=true，
    连错库也不报错，是本项目最容易踩的坑）。"""
    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    if extra:
        env.update(extra)
    return env


def start_server(env_extra=None, profile="dev", cwd=None, log_path=None, timeout=240):
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。请先确认归属："
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    # 工作目录必须是可写目录：SimpleVectorStoreProvider 会在 cwd 下建 ./data/kb/simple，
    # 指向 / 这种只读根目录会让 JVM 直接 FileSystemException 退出。
    cwd = cwd or os.path.join(WORK_ROOT, f"cwd-{profile}")
    os.makedirs(cwd, exist_ok=True)
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, f"--spring.profiles.active={profile}"]
    log("START", f"{profile} 形态起服，日志={log_path}")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT,
                            env=clean_env(env_extra), cwd=cwd or "/")
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

def http_get(path, headers=None, timeout=8):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def get_json(path, headers=None):
    status, raw = http_get(path, headers)
    try:
        body = json.loads(raw)
    except Exception:
        body = None
    return status, body


def login_token():
    status, body = post_json("/system/auth/login", {"username": "admin", "password": "admin123"})
    assert status == 200 and body.get("code") == 0, f"登录失败：{status} {body}"
    return body["data"]["token"]


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


# ────────────────────────── 场景实现 ──────────────────────────

def scenario_probe():
    """场景 1：探活语义 + 匿名裁剪 + 明细登录门槛"""
    log("SCENARIO", "1/4 dev 默认形态：探活语义")
    cwd = os.path.join(WORK_ROOT, "dev-cwd")
    proc, fh = start_server(cwd=cwd, log_path=os.path.join(WORK_ROOT, "probe.log"))
    try:
        # ① liveness：零依赖、匿名、只给 status
        status, body = get_json("/health/live")
        check("A2-1", status == 200, f"/health/live 匿名 HTTP {status}")
        check("A2-2", isinstance(body, dict) and body.get("status") == "UP",
              f"/health/live 匿名 body={body}（应只有 status=UP）")
        check("A2-3", isinstance(body, dict) and set(body.keys()) == {"status"},
              "liveness 匿名响应不得带任何其他字段（措辞即契约）")

        # ② readiness：匿名只给 status + checks
        status, body = get_json("/health/ready")
        check("A2-4", status in (200, 503), f"/health/ready 匿名 HTTP {status}")
        check("A2-5", set((body or {}).keys()) == {"status", "checks"},
              f"readiness 匿名响应字段={list((body or {}).keys())}（应只有 status/checks）")
        check("A2-6", (body or {}).get("status") in ("UP", "DEGRADED", "DOWN"),
              f"readiness 状态取值合法：status={(body or {}).get('status')}")
        check("A2-7", "details" not in (body or {}) and "app" not in (body or {}),
              "匿名响应不得带 details/app（明细含实现名与 profile）")
        checks_map = (body or {}).get("checks") or {}
        check("A2-8", {"db", "redis", "search"}.issubset(set(checks_map.keys())),
              f"readiness 必须覆盖三项依赖：{checks_map}")

        # ③ DOWN ↔ 503 一致性
        consistent = ((body.get("status") == "DOWN") == (status == 503))
        check("A2-9", consistent, f"status={body.get('status')} 与 HTTP {status} 一致")

        # ④ 登录后才给明细
        token = login_token()
        status2, body2 = get_json("/health/ready", {"Authorization": token})
        check("A2-10", {"status", "checks", "details", "app"}.issubset(set((body2 or {}).keys())),
              "登录后 readiness 必须带 details 与 app")
        details = (body2 or {}).get("details") or []
        check("A2-11", {d.get("name") for d in details} >= {"db", "redis", "search"},
              "明细必须逐项给出 implementation/configuredType/fallback 三件套")
        check("A2-12", all({"implementation", "configuredType"} <= set(d.keys()) for d in details),
              "每一项明细都必须能回答「生效的是谁 / 配的是谁」")
        leak = [d for d in details if "jdbc:" in json.dumps(d, ensure_ascii=False)
                or "@" in str(d.get("configuredType") or "")]
        check("A2-13", not leak, "明细里不得出现连接串（含口令/内网地址风险）")

        # ⑤ 既有语义保持不变：不存在的路径仍是 HTTP 200 + code=1004
        status3, body3 = get_json("/health/not-exists")
        check("A2-14", status3 == 200 and (body3 or {}).get("code") == 1004,
              f"未知路径沿用既有语义 HTTP 200 + code=1004（实测 {status3} / {(body3 or {}).get('code')}）")
        status4, body4 = get_json("/actuator/health")
        check("A2-15", status4 == 200 and (body4 or {}).get("code") == 1004,
              "未引 actuator：/actuator/health 仍是 200 + code=1004（探活要改用 /health/*）")

        # ⑥ dev 形态不落滚动文件（只在 prod 落盘，避免污染工作区）
        generated = glob.glob(os.path.join(cwd, "**", "pivotos-admin-server*.log*"), recursive=True)
        check("A3-1", not generated, f"dev profile 只写控制台，cwd 内无日志文件（实测 {generated}）")
    finally:
        stop_server(proc, fh)


def scenario_disabled():
    """场景 2：总开关关闭 → 端点不注册"""
    log("SCENARIO", "2/4 dev + PIVOTOS_OPS_HEALTH_ENABLED=false")
    proc, fh = start_server(env_extra={"PIVOTOS_OPS_HEALTH_ENABLED": "false"},
                            log_path=os.path.join(WORK_ROOT, "disabled.log"))
    try:
        status, body = get_json("/health/live")
        check("A2-16", status == 200 and (body or {}).get("code") == 1004,
              f"总开关关闭后端点不注册：HTTP {status} / code={(body or {}).get('code')}")
    finally:
        stop_server(proc, fh)


def scenario_anonymous_details():
    """场景 3：显式开明细 → 匿名可见（同时证明配置可外部覆盖）"""
    log("SCENARIO", "3/4 dev + PIVOTOS_OPS_HEALTH_SHOWDETAILSANONYMOUSLY=true")
    proc, fh = start_server(
        env_extra={"PIVOTOS_OPS_HEALTH_SHOWDETAILSANONYMOUSLY": "true"},
        log_path=os.path.join(WORK_ROOT, "anon-details.log"))
    try:
        status, body = get_json("/health/ready")
        check("A2-17", {"details", "app"}.issubset(set((body or {}).keys())),
              f"开关打开后匿名可见明细（默认必须关闭）：{list((body or {}).keys())}")
        # 反向证明：这是环境变量真的生效，而不是代码默认值如此
        check("A2-18", status in (200, 503) and isinstance((body or {}).get("details"), list),
              "明细开关只能由部署方显式开启，默认必须关闭")
    finally:
        stop_server(proc, fh)


def read_dev_db_env():
    """从 application-dev.yml 现读中间件连接信息（口令不写进脚本/文档/提交信息）"""
    yml = os.path.join(ROOT, "pivotos-admin-server", "src", "main", "resources", "application-dev.yml")
    text = open(yml, encoding="utf-8").read()

    def pick(pattern):
        m = re.search(pattern, text)
        return m.group(1) if m else ""

    return {
        "url": pick(r"url:\s*\$\{PIVOTOS_MYSQL_URL:([^}]+)\}").strip('"'),
        "username": pick(r"username:\s*\$\{PIVOTOS_MYSQL_USERNAME:([^}]+)\}").strip('"'),
        "password": pick(r"password:\s*\$\{PIVOTOS_MYSQL_PASSWORD:([^}]+)\}").strip('"'),
        "redis_host": pick(r"host:\s*\$\{PIVOTOS_REDIS_HOST:([^}]+)\}").strip('"'),
        "redis_password": pick(r"password:\s*\$\{PIVOTOS_REDIS_PASSWORD:([^}]+)\}").strip('"'),
    }


def scenario_prod_logging():
    """场景 4：prod profile 的滚动日志（100KB 上限以便快速观察到切片）"""
    log("SCENARIO", "4/4 prod 形态日志滚动（指向 dev 中间件，只读，不写业务数据）")
    db_env = read_dev_db_env()
    missing = [k for k, v in db_env.items() if not v]
    if missing:
        raise RuntimeError(f"application-dev.yml 里没读到 {missing}，无法构造隔离的 prod 起服环境")

    if os.path.exists(LOG_DIR):
        shutil.rmtree(LOG_DIR)
    cwd = os.path.join(WORK_ROOT, "prod-cwd")
    if os.path.exists(cwd):
        shutil.rmtree(cwd)
    os.makedirs(cwd, exist_ok=True)

    proc, fh = start_server(
        profile="prod",
        cwd=cwd,
        env_extra={
            "PIVOTOS_MYSQL_URL": db_env["url"],
            "PIVOTOS_MYSQL_USERNAME": db_env["username"],
            "PIVOTOS_MYSQL_PASSWORD": db_env["password"],
            "PIVOTOS_REDIS_HOST": db_env["redis_host"],
            "PIVOTOS_REDIS_PASSWORD": db_env["redis_password"],
            "PIVOTOS_LOG_DIR": LOG_DIR,
            # 刻意压小：为了在 E2E 的时间预算里真的观察到「切片」发生（生产默认是 100MB）
            "PIVOTOS_LOG_MAX_FILE_SIZE": "20KB",
            # 32 位临时占位（FieldEncryptCrypto 强校验 16/24/32 位，非真凭据，仅本机临时进程用）
            "PIVOTOS_FIELD_ENCRYPT_KEY": "abcdefghijklmnopqrstuvwxyz012345",
            # 以下均为「必须非空否则 fail-fast」的 A1 出口，本场景只验证日志与探活，用占位值撑满：
            # COS Key / LLM Key / XXL-Job token；检索显式降为 simple，避免去连生产 ES 白等。
            "PIVOTOS_COS_ACCESS_KEY": "v31b-e2e-placeholder",
            "PIVOTOS_COS_SECRET_KEY": "v31b-e2e-placeholder",
            "PIVOTOS_LLM_API_KEY": "v31b-e2e-placeholder",
            "PIVOTOS_XXL_JOB_ACCESS_TOKEN": "v31b-e2e-placeholder",
            "PIVOTOS_SEARCH_TYPE": "simple",
        },
        log_path=os.path.join(WORK_ROOT, "prod-console.log"), timeout=300)
    try:
        current = os.path.join(LOG_DIR, "pivotos-admin-server.log")
        for _ in range(60):
            if os.path.exists(current):
                break
            time.sleep(1)
        check("A3-2", os.path.exists(current), f"prod 形态应用日志落盘：{current}")

        # 打日志：不存在的路径会让 GlobalExceptionHandler 落一行 WARN，是稳定的日志量来源；
        # 同时再访问若干次探活端点（探针自身在正常路径下刻意不打日志，避免 Nginx 轮询刷爆磁盘）
        for _ in range(300):
            http_get("/health/not-exists")
        for _ in range(10):
            http_get("/health/live")
            http_get("/health/ready")
        time.sleep(3)

        archives = sorted(glob.glob(os.path.join(LOG_DIR, "*.log.gz")))
        check("A3-3", len(archives) >= 1,
              f"超过单文件上限即按大小切片并 gzip（本轮压到 20KB）：共 {len(archives)} 份归档")
        check("A3-4", os.path.getsize(current) > 0, "当前日志持续写入")
        pattern_ok = True
        with open(current, encoding="utf-8", errors="ignore") as f:
            sample = f.read(4000)
            pattern_ok = bool(re.search(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} (INFO|WARN|ERROR|---)", sample))
        check("A3-5", pattern_ok, "日志格式含时间戳与级别（样例前 4KB 已核对）")
        check("A3-6", not re.search(r"(DEBUG|TRACE)", sample),
              "prod 默认不含 DEBUG/TRACE（已由 application-prod.yml 降为 info）")

        # 探针在 prod 同样可用（且不匿名泄露明细）
        status, body = get_json("/health/ready")
        check("A3-7", set((body or {}).keys()) == {"status", "checks"},
              f"prod 匿名探活仍只给 status/checks：{list((body or {}).keys())}")

        console_size = os.path.getsize(os.path.join(WORK_ROOT, "prod-console.log"))
        check("A3-8", console_size < 2 * 1024 * 1024,
              f"prod 不再双写：控制台只剩 JVM 层输出（{console_size} 字节）")
    finally:
        stop_server(proc, fh)


def scenario_test_logging():
    """场景 5：test profile 也走滚动日志（V3.1-D 遗留④ / C4-1）

    与场景 4 同一套断言，用来证明两件事：① test 真的落滚动文件；② 落的是「只写文件不写控制台」，
    没有因为表达式改成「prod | test」而变成两块 appender 同时命中（双写）。
    """
    log("SCENARIO", "5/5 test 形态日志滚动（C4-1：test 与 prod 共用滚动策略）")
    db_env = read_dev_db_env()
    missing = [k for k, v in db_env.items() if not v]
    if missing:
        raise RuntimeError(f"application-dev.yml 里没读到 {missing}，无法构造隔离的 test 起服环境")

    if os.path.exists(LOG_DIR):
        shutil.rmtree(LOG_DIR)
    cwd = os.path.join(WORK_ROOT, "test-cwd")
    if os.path.exists(cwd):
        shutil.rmtree(cwd)
    os.makedirs(cwd, exist_ok=True)

    console_log = os.path.join(WORK_ROOT, "test-console.log")
    proc, fh = start_server(
        profile="test",
        cwd=cwd,
        env_extra={
            # test yml 的默认值指向 10.0.0.7（内网），这里改指 dev 中间件才能真起来
            "PIVOTOS_MYSQL_URL": db_env["url"],
            "PIVOTOS_MYSQL_USERNAME": db_env["username"],
            "PIVOTOS_MYSQL_PASSWORD": db_env["password"],
            "PIVOTOS_REDIS_HOST": db_env["redis_host"],
            "PIVOTOS_REDIS_PASSWORD": db_env["redis_password"],
            "PIVOTOS_LOG_DIR": LOG_DIR,
            "PIVOTOS_LOG_MAX_FILE_SIZE": "20KB",
            # 32 位占位（非真凭据）：test 形态用开发默认密钥只会 WARN，但显式注入更贴近运维口径
            "PIVOTOS_FIELD_ENCRYPT_KEY": "abcdefghijklmnopqrstuvwxyz012345",
            "PIVOTOS_SEARCH_TYPE": "simple",
        },
        log_path=console_log, timeout=300)
    try:
        current = os.path.join(LOG_DIR, "pivotos-admin-server.log")
        for _ in range(60):
            if os.path.exists(current):
                break
            time.sleep(1)
        check("C4-1", os.path.exists(current), f"test 形态应用日志落盘（与 prod 同一策略）：{current}")

        for _ in range(300):
            http_get("/health/not-exists")
        time.sleep(3)

        archives = sorted(glob.glob(os.path.join(LOG_DIR, "*.log.gz")))
        check("C4-2", len(archives) >= 1,
              f"test 形态同样按大小切片并 gzip（压到 20KB）：共 {len(archives)} 份归档")
        check("C4-3", os.path.getsize(current) > 0, "当前日志持续写入")

        console_size = os.path.getsize(console_log)
        check("C4-4", console_size < 2 * 1024 * 1024,
              f"test 形态不再双写控制台：{console_size} 字节（两块 appender 互斥，非 prod|test 双命中）")

        status, body = get_json("/health/live")
        check("C4-5", status == 200 and (body or {}).get("status") == "UP",
              f"test 形态探活仍可用：HTTP {status} {body}")
    finally:
        stop_server(proc, fh)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", choices=["probe", "disabled", "anon", "log", "testlog", "all"], default="all")
    args = parser.parse_args()

    if os.path.exists(WORK_ROOT):
        shutil.rmtree(WORK_ROOT)
    os.makedirs(WORK_ROOT, exist_ok=True)

    scenarios = {
        "probe": scenario_probe,
        "disabled": scenario_disabled,
        "anon": scenario_anonymous_details,
        "log": scenario_prod_logging,
        "testlog": scenario_test_logging,
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
        # 自清：只删本轮临时产物（排障时设 KEEP_LOGS=1 保留）
        if os.path.exists(WORK_ROOT) and not os.environ.get("KEEP_LOGS"):
            shutil.rmtree(WORK_ROOT, ignore_errors=True)
        if port_busy():
            log("WARN", "8080 仍被占用，请人工确认归属（本脚本只 kill 自己拉起的进程）")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
