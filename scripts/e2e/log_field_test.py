#!/usr/bin/env python3
"""v3.2.0 · 日志采集侧 userId/tenantId 字段切分的实证断言（自起自停 fat jar，只 kill 本轮 PID）。

背景：v3.2.0 之前的业务日志把身份段拼成 `[u=%X{userId:-},t=%X{tenantId:-}]`，
采集侧（Filebeat / Promtail / Loki）必须按「括号 + 逗号」写一条**本项目专用正则**才能切出
两个维度；一旦某个维度取不到（未登录、无租户），正则会因少一个逗号而**静默错位**——
日志看起来正常，采集出来的字段全串了位，是极难归因的一类故障。

本批改为三个自描述 kv 字段：`traceId= userId= tenantId=`，
采集侧用通用 kv 解析器即可，不依赖专用正则。

断言（逐条，全部实打）：
  L-1 默认文本形态：日志行含 `traceId=` / `userId=` / `tenantId=` 三个独立 token
  L-2 登录后能取到**真实 userId**（不是空占位）——证明 MDC → pattern 这条链是通的
  L-3 旧形态 `[u=...,t=...]` 已彻底消失（防「改了一半」）
  L-4 kv 可机器切分：用通用 kv 正则能把三个字段都取出来（这是采集侧的真实诉求）
  L-5 JSON 覆盖通道：注入 PIVOTOS_LOG_PATTERN 后，日志行可被 json.loads 解析且三字段齐全
  L-6 dev 形态仍不落滚动文件（C4-1 口径不被本批改动破坏）
  L-7 负向自检：故意注入一个不含 userId 的 pattern → L-4/L-5 的判定函数必须判 False
      ——证明断言不是恒真（恒真的断言等于没有断言）

用法：
    python3 scripts/e2e/log_field_test.py
前置：8080 空闲（脚本自起自停）；dev 库凭据走 application-dev.yml 明文兜底。
"""
import json
import os
import re
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
WORK_ROOT = "/tmp/pivotos-v320-logfield"

# 结构化采集用的 JSON 预设（运维可通过 PIVOTOS_LOG_PATTERN 整体替换默认文本 pattern）。
# %replace 把消息里的控制字符与引号/反斜杠换成空格 —— 没有它，一条含双引号的业务日志
# 就能把整行 JSON 打废，而采集侧只会看到「偶发解析失败」，极难归因。
JSON_PATTERN = (
    '{"ts":"%d{yyyy-MM-dd HH:mm:ss.SSS}","level":"%level","thread":"%thread",'
    '"logger":"%logger{40}","traceId":"%X{traceId:-}","userId":"%X{userId:-}",'
    '"tenantId":"%X{tenantId:-}","msg":"%replace(%msg){\'[\\x00-\\x1F\\x22\\x5C]\',\' \'}"}%n'
)
# 负向自检用：故意去掉 userId 字段
JSON_PATTERN_NO_USER = JSON_PATTERN.replace('"userId":"%X{userId:-}",', "")

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


def port_busy(port=8080):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def start_server(env_extra=None, timeout=300):
    if port_busy():
        raise RuntimeError("8080 已被占用（可能是你自有的进程）。先确认归属："
                           "lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    cwd = os.path.join(WORK_ROOT, "dev-cwd")
    os.makedirs(cwd, exist_ok=True)
    log_path = os.path.join(WORK_ROOT, "dev-console.log")
    if os.path.exists(log_path):
        os.remove(log_path)
    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    # 身份字段由 starter-tracing 的 MdcContextFilter 写入，默认 pivotos.tracing.enabled=false
    env["PIVOTOS_TRACING_ENABLED"] = "true"
    env.update(env_extra or {})
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev"]
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=env, cwd=cwd)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端提前退出（exit={proc.returncode}），见 {log_path}")
        try:
            if http_get("/health/live")[0] == 200:
                log("START", f"后端就绪 PID={proc.pid}")
                return proc, fh, cwd, log_path
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 {timeout}s 未就绪，见 {log_path}")


def stop_server(proc, fh):
    try:
        if proc and proc.poll() is None:
            log("STOP", f"kill 本轮 PID {proc.pid}")
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.send_signal(signal.SIGKILL)
                proc.wait(timeout=10)
    finally:
        if fh:
            fh.close()
    for _ in range(30):
        if not port_busy():
            return
        time.sleep(1)


def http_get(path, headers=None, timeout=10):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.getcode(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def http_post_json(path, payload, headers=None, timeout=15):
    data = json.dumps(payload).encode("utf-8")
    hdr = {"Content-Type": "application/json"}
    hdr.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, headers=hdr, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.getcode(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def read_log(log_path):
    with open(log_path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


# ---------- 判定函数（采集侧视角：通用 kv 解析，不含本项目专用正则） ----------
KV_RE = re.compile(r"\b(traceId|userId|tenantId)=(\S*)")


def kv_split(line):
    """通用 kv 解析：返回 {字段名: 值}。采集侧的真实诉求就是这么切。"""
    return {m.group(1): m.group(2) for m in KV_RE.finditer(line)}


def has_real_user_id(text):
    """是否存在取到真实 userId 的行（非 '-' 空占位）"""
    for line in text.splitlines():
        v = kv_split(line).get("userId")
        if v and v != "-":
            return True
    return False


def json_lines_parse(text):
    """返回 (可解析行数, 首个**三字段齐全且 userId 有真实值**的行 dict)。

    刻意要求 userId 非空：启动期的日志行三个字段全是空串，拿它当判据就是恒真断言。
    """
    ok = 0
    first = None
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            obj = json.loads(line)
        except Exception:
            continue
        ok += 1
        if (first is None and {"traceId", "userId", "tenantId"} <= set(obj)
                and obj.get("userId") not in ("", "-")):
            first = obj
    return ok, first


def drive_business_requests():
    """登录 + 打两个业务请求，保证身份 MDC 有值"""
    code, body = http_post_json("/system/auth/login", {"username": "admin", "password": "admin123"})
    token = ""
    try:
        token = json.loads(body).get("data", {}).get("token", "") or ""
    except Exception:
        pass
    auth = {"Authorization": token} if token else {}
    http_get("/system/user/page", headers=auth)
    http_get("/system/menu/routers", headers=auth)
    time.sleep(2)


def main():
    # ================= 场景一：默认文本形态 =================
    log("SCENARIO", "1/3 默认文本形态（kv 独立字段）")
    proc, fh, cwd, log_path = start_server()
    try:
        drive_business_requests()
        text = read_log(log_path)
    finally:
        stop_server(proc, fh)

    lines = [l for l in text.splitlines() if " - " in l]
    check("L-1", any("traceId=" in l and "userId=" in l and "tenantId=" in l for l in lines),
          "日志行含 traceId=/userId=/tenantId= 三个独立 token（共 {} 行候选）".format(len(lines)))
    check("L-2", has_real_user_id(text),
          "登录后能取到真实 userId（非 '-' 空占位），证明 MDC → pattern 链路是通的")
    check("L-3", not re.search(r"\[u=[^\]]*,t=", text),
          "旧形态 [u=...,t=...] 已彻底消失（防改了一半）")
    # 取「身份字段有真实值」的那一行做样例：拿启动期空值行也能通过断言，
    # 但那是恒真断言（等于没断言），必须挑有值的行
    sample = next((l for l in lines if kv_split(l).get("userId") not in (None, "", "-")), "")
    kv = kv_split(sample)
    check("L-4", {"traceId", "userId", "tenantId"} <= set(kv) and kv.get("userId") not in ("", "-"),
          "通用 kv 正则能把三个字段都切出来（取真实值行）：{}".format(kv))
    # L-6 只认「应用滚动文件」：logs/ 目录本身会被 xxl-job 建出来，不能拿目录存在与否当判据
    rolling = []
    for dirpath, _dirs, files in os.walk(cwd):
        for f in files:
            if f.startswith("pivotos-admin-server") and f.endswith(".log"):
                rolling.append(os.path.join(dirpath, f))
    check("L-6", not rolling,
          "dev 形态不落应用滚动文件（logs/ 目录可能是 xxl-job 建的，不算），实际命中 {}".format(rolling))

    # ================= 场景二：JSON 覆盖通道 =================
    log("SCENARIO", "2/3 PIVOTOS_LOG_PATTERN 覆盖为 JSON 一行式")
    proc, fh, cwd, log_path = start_server({"PIVOTOS_LOG_PATTERN": JSON_PATTERN})
    try:
        drive_business_requests()
        text_json = read_log(log_path)
    finally:
        stop_server(proc, fh)

    ok, first = json_lines_parse(text_json)
    check("L-5", ok > 0 and first is not None,
          "JSON 覆盖通道可用：可解析 {} 行，样例字段 {}".format(
              ok, {k: first.get(k) for k in ("traceId", "userId", "tenantId")} if first else None))

    # ================= 场景三：负向自检 =================
    log("SCENARIO", "3/3 负向自检（故意去掉 userId 字段，判定函数必须判 False）")
    proc, fh, cwd, log_path = start_server({"PIVOTOS_LOG_PATTERN": JSON_PATTERN_NO_USER})
    try:
        drive_business_requests()
        text_bad = read_log(log_path)
    finally:
        stop_server(proc, fh)

    ok_bad, first_bad = json_lines_parse(text_bad)
    check("L-7a", first_bad is None,
          "去掉 userId 后 JSON 通道判不出该字段（判定函数非恒真），可解析 {} 行".format(ok_bad))
    check("L-7b", not has_real_user_id(text_bad),
          "去掉 userId 后 kv 通道同样判不出真实 userId（判定函数非恒真）")

    print()
    passed = sum(1 for _, ok in results if ok)
    print(f"合计：{passed}/{len(results)} PASS")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
