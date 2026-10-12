#!/usr/bin/env python3
"""V3-S4 · 业务 Facade HTTP 化 E2E：真 fat jar + 真业务契约 + 真 HTTP 往返。

与 starter-cloud-api 里的 FacadeHttpBusinessRoundTripTest 分工：
  · 单测侧用「同构 fixture」覆盖 marshalling 难点（泛型/时间/Optional/异常/单元素数组），
    因为契约包不能依赖任何 plugin 的 *-api 模块；
  · 本脚本用「真实业务契约」覆盖真实语义（真连 dev 库、真 Bean、真 Flyway 环境），
    两者缺一不可 —— 只做单测会漏掉「真实 DTO 上有没有不可序列化字段」这类问题。

覆盖（单场景，脚本自起自停一套 dev 后端，8080）：
  ① 起服冒烟：Flyway validated + database=pivotos_dev + 登录 code=0 + 匿名 /sse 401
  ② 预置组一致性：脚本里的白名单直接解析自 FacadeHttpPresets.java（防两处漂移）
  ③ 安全口径：无凭证 403 / 错凭证 403 / 白名单外接口 404
  ④ 只读业务方法真调：system 5 个 + file/ai/kb/generator/migration 各取代表
  ⑤ D1 泛型：listByIds 传 [[1]]（集合是**一个**参数）不退化成 ClassCastException
  ⑥ 单标量参数（getByUsername）不被摊平 —— 摊平后是 NO_METHOD
  ⑦ D3 上下文：ContextFacade 带身份头/不带身份头结果必须不同
  ⑧ P4 负向：写方法（send / generateToProject 等）未配置即 404
  ⑨ 停服只 kill 本轮 PID

只读脚本：全程不写库（白名单只开只读方法），无需数据清理。

用法：
    python3 scripts/e2e/facade_http_business_test.py
"""
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
HERE = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.abspath(os.path.join(HERE, "..", "..", "pivotos-admin-server", "target",
                                   "pivotos-admin-server.jar"))
PRESETS_JAVA = os.path.abspath(os.path.join(
    HERE, "..", "..", "pivotos-starters", "pivotos-starter-cloud-api", "src", "main", "java",
    "com", "pivotos", "starter", "cloud", "api", "facade", "FacadeHttpPresets.java"))
LOG_DIR = "/tmp/pivotos-facade-http-e2e"
LOG_PATH = os.path.join(LOG_DIR, "server.log")

TOKEN = "facade-http-e2e-token"
CONTEXT_FACADE = "com.pivotos.common.api.context.ContextFacade"

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


# ────────────────────────── 预置组解析（防脚本与 Java 常量漂移） ──────────────────────────

def parse_presets():
    """直接从 FacadeHttpPresets.java 解析 READ_ONLY_BUSINESS，避免脚本里再抄一份。"""
    with open(PRESETS_JAVA, encoding="utf-8") as fh:
        src = fh.read()
    start = src.index("READ_ONLY_BUSINESS = List.of(")
    block = src[start:src.index(");", start)]
    # 多行字符串拼接（"a" + "b"）先合并，再取全部字面量
    merged = re.sub(r'"\s*\+\s*"', "", block)
    entries = [s for s in re.findall(r'"([^"]*)"', merged) if s.strip()]
    return entries


# ────────────────────────── 后端生命周期 ──────────────────────────

def port_busy(port=8080) -> bool:
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def start_server(whitelist):
    if port_busy():
        raise RuntimeError(
            "8080 已被占用（可能是用户自有进程或上一轮遗留）。"
            "先确认归属：lsof -Pan -i:8080 -sTCP:LISTEN；本脚本只启停自己拉起的进程。")
    os.makedirs(LOG_DIR, exist_ok=True)
    cmd = [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
           "-jar", JAR, "--spring.profiles.active=dev",
           "--pivotos.cloud.facade-http.enabled=true",
           f"--pivotos.cloud.context.internal-token={TOKEN}",
           "--pivotos.cloud.context.restore-tenant=true",
           "--pivotos.cloud.context.restore-login=true",
           f"--pivotos.cloud.facade-http.interfaces={CONTEXT_FACADE},{whitelist}"]
    env = dict(os.environ)
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    fh = open(LOG_PATH, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=env)
    log("START", f"PID={proc.pid} 日志={LOG_PATH}")

    deadline = time.time() + 240
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端提前退出（exit={proc.returncode}），见 {LOG_PATH}")
        try:
            req = urllib.request.Request(
                f"{BASE}/system/auth/login",
                data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200 and json.loads(resp.read()).get("code") == 0:
                    log("START", "后端就绪")
                    return proc, fh
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 240s 未就绪，见 {LOG_PATH}")


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
        try:
            urllib.request.urlopen(f"{BASE}/system/auth/login", timeout=2)
        except Exception:
            return
        time.sleep(1)


# ────────────────────────── HTTP 辅助 ──────────────────────────

def http(path, body=None, headers=None, method="POST"):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"{BASE}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def facade(iface, method, args=None, headers=None):
    return http(f"/__facade/{iface}/{method}", body=args if args is not None else [],
                headers=headers)


def internal(tenant=None, user=None, username=None, token=TOKEN):
    h = {"X-PivotOS-Internal": token}
    if tenant is not None:
        h["X-PivotOS-Tenant-Id"] = str(tenant)
    if user is not None:
        h["X-PivotOS-User-Id"] = str(user)
    if username is not None:
        h["X-PivotOS-Username"] = username
    return h


def server_log_text():
    with open(LOG_PATH, "r", errors="replace") as fh:
        return fh.read()


# ────────────────────────── 主流程 ──────────────────────────

def main():
    presets = parse_presets()
    check("② 预置组", len(presets) >= 12,
          f"解析到 {len(presets)} 条预置白名单（FacadeHttpPresets.READ_ONLY_BUSINESS）")
    whitelist = ",".join(presets)

    proc, fh = None, None
    try:
        proc, fh = start_server(whitelist)

        # ① 起服冒烟
        text = server_log_text()
        check("① Flyway", "Successfully validated" in text or "up to date" in text,
              "Flyway 校验通过（未使用 validate-on-migrate=false）")
        check("① 数据源", "database=pivotos_dev" in text, "生效数据源 database=pivotos_dev")
        check("① 端点", "[CLOUD][facade-http] 已注册 MVC 映射" in text, "Facade HTTP 端点已注册")
        status, body = http("/system/auth/login", {"username": "admin", "password": "admin123"})
        check("① 登录", status == 200 and body.get("code") == 0, "登录 code=0")
        status, _ = http("/sse", method="GET")
        check("① 匿名 /sse", status == 401, "匿名 /sse 401")

        # ③ 安全口径
        status, body = facade("com.pivotos.system.api.facade.IUserFacade", "getByUsername",
                              ["admin"], headers={})
        check("③ 无凭证", status == 403 and body.get("error") == "FORBIDDEN", "缺内部凭证 403")
        status, body = facade("com.pivotos.system.api.facade.IUserFacade", "getByUsername",
                              ["admin"], headers=internal(token="wrong-token"))
        check("③ 错凭证", status == 403 and body.get("error") == "FORBIDDEN", "错凭证 403")
        status, body = facade("com.pivotos.migration.api.IMigrationFacade", "whatever",
                              [], headers=internal())
        check("③ 白名单外", status == 404 and body.get("error") == "NOT_FOUND",
              "白名单外接口 404（且不可用于探测 classpath）")

        # ④⑥ 只读业务方法真调（getByUsername 同时是「单标量参数不摊平」的判据）
        status, body = facade("com.pivotos.system.api.facade.IUserFacade", "getByUsername",
                              ["admin"], headers=internal())
        check("④⑥ 单标量参数", status == 200 and body.get("ok") is True
              and (body.get("data") or {}).get("username") == "admin",
              "IUserFacade#getByUsername 返回 admin（单元素数组未被摊平）")

        # ⑤ 泛型实参：集合是**一个**参数，必须传 [[...]]
        status, body = facade("com.pivotos.system.api.facade.IUserFacade", "listByIds",
                              [[1]], headers=internal())
        check("⑤ 泛型", status == 200 and body.get("ok") is True
              and isinstance(body.get("data"), list) and len(body["data"]) == 1
              and body["data"][0].get("id") == 1,
              "IUserFacade#listByIds([[1]]) 命中（Collection<Long> 泛型未擦除）")

        status, body = facade("com.pivotos.system.api.facade.IDeptFacade", "getById",
                              [1], headers=internal())
        check("④ Dept", status == 200 and body.get("ok") is True, "IDeptFacade#getById 可调")

        status, body = facade("com.pivotos.system.api.facade.IDictFacade", "listDictData",
                              ["sys_user_sex"], headers=internal())
        check("④ Dict", status == 200 and isinstance(body.get("data"), list),
              "IDictFacade#listDictData 返回列表")

        status, body = facade("com.pivotos.system.api.facade.IStatsFacade", "systemStats",
                              [], headers=internal())
        check("④ Stats", status == 200 and isinstance((body.get("data") or {}).get("userCount"), int),
              "IStatsFacade#systemStats 返回 userCount")

        status, body = facade("com.pivotos.system.api.facade.IRoleFacade", "listRoleCodesByUserId",
                              [1], headers=internal())
        check("④ Role", status == 200 and isinstance(body.get("data"), list),
              "IRoleFacade#listRoleCodesByUserId 返回列表")

        status, body = facade("com.pivotos.file.api.facade.IFileFacade", "storageStats",
                              [], headers=internal())
        check("④ File", status == 200 and body.get("ok") is True, "IFileFacade#storageStats 可调")

        status, body = facade("com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade", "kbStats",
                              [], headers=internal())
        check("④ KB", status == 200 and body.get("ok") is True, "IKnowledgeBaseFacade#kbStats 可调")

        status, body = facade("com.pivotos.ai.api.facade.IAiFacade", "resolveModel",
                              [""], headers=internal())
        check("④ AI", status == 200 and isinstance(body.get("data"), str),
              "IAiFacade#resolveModel 返回字符串")

        status, body = facade("com.pivotos.migration.api.codeindex.ICodeIndexFacade", "stats",
                              [], headers=internal())
        check("④ CodeIndex", status == 200 and isinstance(body.get("data"), dict),
              "ICodeIndexFacade#stats 返回 Map（Optional 类方法未受影响）")

        # ④ 依赖登录态的只读方法（带身份头调用，确认不报错）
        status, body = facade("com.pivotos.workflow.api.IWorkflowFacade", "countPending",
                              [], headers=internal(tenant=1, user=1, username="admin"))
        check("④ Workflow", status == 200 and isinstance(body.get("data"), int),
              "IWorkflowFacade#countPending 返回数字")
        status, body = facade("com.pivotos.message.api.facade.IMessageFacade", "countUnread",
                              [1], headers=internal(tenant=1, user=1, username="admin"))
        check("④ Message", status == 200 and isinstance(body.get("data"), int),
              "IMessageFacade#countUnread 返回数字")

        # ⑦ D3 上下文对照
        status, with_id = facade(CONTEXT_FACADE, "getLoginUserId", [],
                                 headers=internal(tenant=12, user=1001, username="alice"))
        status2, without_id = facade(CONTEXT_FACADE, "getLoginUserId", [], headers=internal())
        check("⑦ D3", status == 200 and with_id.get("data") == 1001 and without_id.get("data") is None,
              "带身份头读到 1001、不带读到 null（业务方法看到的是入站恢复后的身份）")

        # ⑧ P4 负向：写方法未配置即 404
        for iface, method in [
            ("com.pivotos.message.api.facade.IMessageFacade", "send"),
            ("com.pivotos.generator.service.IGeneratorFacade", "generateToProject"),
            ("com.pivotos.workflow.api.IWorkflowFacade", "approveTask"),
            ("com.pivotos.file.api.facade.IFileFacade", "presignDownload"),
            ("com.pivotos.ai.api.facade.IAiFacade", "chat"),
        ]:
            status, body = facade(iface, method, [], headers=internal())
            check("⑧ 写方法", status == 404 and body.get("error") == "NOT_FOUND",
                  f"未配置的写方法 {method} 404")
    finally:
        try:
            stop_server(proc, fh)
        except Exception as e:  # 停服失败也必须打印结果汇总
            log("WARN", f"停服异常：{e}")

    ok = sum(1 for _, r in results if r)
    log("SUMMARY", f"{ok}/{len(results)} 通过")
    if ok != len(results):
        sys.exit(1)


if __name__ == "__main__":
    main()
