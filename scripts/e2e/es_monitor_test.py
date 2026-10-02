#!/usr/bin/env python3
"""ES 监控 E2E（系统监控 · ES 监控页后端接口 GET /monitor/es）。

覆盖三种形态（每个场景自起自停一套后端，8080）：
  A. 接 ES 9.5.3   —— 175.24.176.176:9200（dev 默认地址，compatibility-mode=false）
  B. 接 ES 7.17.28 —— 175.24.176.176:9201（必须 compatibility-mode=true，否则兼容头被拒）
  C. 未接 / 不可达 ——  10.0.0.7:9200（内网地址，开发机不通）→ 降级 available=false

断言要点：
  · A/B：available=true、implementation=es-java、未回落、健康色/节点/索引/JVM/分片均取到值，
         且节点明细里的 version 与服务端实际版本一致（钉死「多版本采集口径都对」）；
  · C  ：HTTP 200 + code=0 + available=false + reason 非空（**绝不抛异常、绝不 500**）；
  · 全场景：匿名访问被拦截（权限码 monitor:es:list 生效）；
  · 菜单：sys_menu 存在 id=1230 / perms=monitor:es:list（Flyway V1.2.50 已应用）。

口径：脚本只做只读采集与登录，不改任何业务数据；进程只 kill 本轮自己启动的 PID。

用法：
    python3 scripts/e2e/es_monitor_test.py                # 跑全三场景
    python3 scripts/e2e/es_monitor_test.py --only A       # 只跑指定场景
"""
import argparse
import json
import os
import signal
import subprocess
import sys
import time
import urllib.request
import urllib.error

import pymysql
import requests

BASE = "http://localhost:8080"
JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
JAR = os.path.join(os.path.dirname(__file__), "..", "..", "pivotos-admin-server", "target",
                   "pivotos-admin-server.jar")
LOG_DIR = "/tmp/pivotos-es-monitor-e2e"

DB = dict(
    host="175.24.176.176",
    port=3306,
    user="root",
    password="mysql_DNCi3f",
    database="pivotos_dev",
    charset="utf8mb4",
)

# 场景定义：名称 → (ES 地址, 兼容模式, 期望服务端版本号, 期望 available)
SCENARIOS = {
    "A": dict(name="ES 9.5.3 (9200)", uri="http://175.24.176.176:9200", compat="false",
              version="9.5.3", password="Elastic_RGRSPM", expect_available=True),
    "B": dict(name="ES 7.17.28 (9201)", uri="http://175.24.176.176:9201", compat="true",
              version="7.17.28", password="Elastic_P7SSAJ", expect_available=True),
    "C": dict(name="不可达 (10.0.0.7)", uri="http://10.0.0.7:9200", compat="false",
              version=None, password="Elastic_RGRSPM", expect_available=False),
}

results = []


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(tag, cond, msg):
    if cond:
        results.append((tag, True))
        log("PASS", f"{tag} {msg}")
    else:
        results.append((tag, False))
        log("FAIL", f"{tag} {msg}")
        raise AssertionError(f"{tag} 断言失败：{msg}")


# ────────────────────────── 后端生命周期 ──────────────────────────

def start_server(key, scenario):
    os.makedirs(LOG_DIR, exist_ok=True)
    log_path = os.path.join(LOG_DIR, f"server-{key}.log")
    cmd = [
        JAVA,
        "-Djna.library.path=/opt/homebrew/lib",
        "-Djava.awt.headless=true",
        "-jar", os.path.abspath(JAR),
        "--spring.profiles.active=dev",
        "--pivotos.search.type=es-java",
        f"--pivotos.search.es-java.uris={scenario['uri']}",
        f"--pivotos.search.es-java.username=elastic",
        f"--pivotos.search.es-java.password={scenario['password']}",
        f"--pivotos.search.es-java.compatibility-mode={scenario['compat']}",
    ]
    env = dict(os.environ)
    env["TESSDATA_PREFIX"] = "/opt/homebrew/share/tessdata"
    log("START", f"场景 {key} 起服：{' '.join(cmd[6:])}")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, env=env)
    log("START", f"PID={proc.pid} 日志={log_path}")

    deadline = time.time() + 240
    while time.time() < deadline:
        if proc.poll() is not None:
            fh.close()
            raise RuntimeError(f"后端进程提前退出（exit={proc.returncode}），见日志 {log_path}")
        try:
            req = urllib.request.Request(
                f"{BASE}/system/auth/login",
                data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200 and json.loads(resp.read()).get("code") == 0:
                    log("START", "后端就绪")
                    return proc, fh
        except Exception:
            pass
        time.sleep(2)
    stop_server(proc, fh)
    raise RuntimeError(f"后端 240s 内未就绪，见日志 {log_path}")


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
    # 等端口真正释放，避免下一场景启动时端口冲突
    for _ in range(30):
        try:
            with urllib.request.urlopen(f"{BASE}/system/auth/login", timeout=2):
                pass
        except urllib.error.HTTPError:
            return
        except Exception:
            return
        time.sleep(1)


# ────────────────────────── 断言 ──────────────────────────

def login():
    r = requests.post(f"{BASE}/system/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=60)
    body = r.json()
    assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
    return {"Authorization": body["data"]["token"]}


def fetch_es(hdr):
    r = requests.get(f"{BASE}/monitor/es", headers=hdr, timeout=60)
    return r, r.json()


def assert_numbers(key, data):
    """数值类断言：后端 Long 序列化为字符串，统一转 int/float 再比"""
    check(f"{key}-数值", int(data["nodeCount"]) >= 1, f"nodeCount={data['nodeCount']}")
    check(f"{key}-数值", int(data["indexCount"]) >= 1, f"indexCount={data['indexCount']}")
    check(f"{key}-数值", int(data["docCount"]) >= 0, f"docCount={data['docCount']}")
    check(f"{key}-数值", int(data["storeSizeBytes"]) > 0, f"storeSizeBytes={data['storeSizeBytes']}")
    check(f"{key}-数值", int(data["jvmHeapMaxBytes"]) > 0, f"jvmHeapMaxBytes={data['jvmHeapMaxBytes']}")
    check(f"{key}-数值", int(data["shardsActive"]) >= 1, f"shardsActive={data['shardsActive']}")
    check(f"{key}-数值", int(data["shardsActivePrimary"]) >= 1,
          f"shardsActivePrimary={data['shardsActivePrimary']}")
    check(f"{key}-数值", 0 <= int(data["jvmHeapUsedPercent"]) <= 100,
          f"jvmHeapUsedPercent={data['jvmHeapUsedPercent']}")


def run_scenario(key, scenario):
    log("SCENARIO", f"===== 场景 {key}：{scenario['name']} =====")
    proc, fh = start_server(key, scenario)
    try:
        hdr = login()

        # ① 鉴权：匿名访问必须被拦（权限码 monitor:es:list）
        anon = requests.get(f"{BASE}/monitor/es", timeout=60)
        anon_body = anon.json() if anon.headers.get("content-type", "").startswith("application/json") else {}
        check(f"{key}-鉴权", anon_body.get("code") != 0,
              f"匿名访问被拦截（HTTP {anon.status_code}, code={anon_body.get('code')}）")

        # ② 主断言
        r, body = fetch_es(hdr)
        check(f"{key}-HTTP", r.status_code == 200 and body.get("code") == 0,
              f"HTTP {r.status_code} code={body.get('code')}（降级也必须 200 + code=0，绝不能 500）")
        data = body["data"]
        check(f"{key}-契约", isinstance(data.get("available"), bool), "available 是布尔")
        check(f"{key}-契约", data.get("nodes") is not None and data.get("indices") is not None,
              "明细列表非 null（前端 v-for 安全）")

        if scenario["expect_available"]:
            check(f"{key}-可用", data["available"] is True, "available=true")
            check(f"{key}-实现", data.get("implementation") == "es-java",
                  f"implementation={data.get('implementation')}")
            check(f"{key}-实现", data.get("configuredType") == "es-java",
                  f"configuredType={data.get('configuredType')}")
            check(f"{key}-实现", data.get("fallback") is False, "未发生回落")
            check(f"{key}-健康色", data.get("status") in ("green", "yellow", "red"),
                  f"status={data.get('status')}")
            check(f"{key}-版本", data.get("serverVersion") == scenario["version"],
                  f"serverVersion={data.get('serverVersion')}（期望 {scenario['version']}）")
            assert_numbers(key, data)

            nodes = data.get("nodes") or []
            check(f"{key}-节点", len(nodes) >= 1, f"节点明细 {len(nodes)} 条")
            check(f"{key}-节点", all(n.get("version") == scenario["version"] for n in nodes),
                  f"节点明细版本号一致：{[n.get('version') for n in nodes]}")
            check(f"{key}-节点", any(n.get("master") for n in nodes), "存在主节点标记")

            indices = data.get("indices") or []
            check(f"{key}-索引", len(indices) >= 1, f"索引明细 {len(indices)} 条")
            check(f"{key}-索引", all(not (i.get("index") or "").startswith(".") for i in indices),
                  f"系统内建索引已排除：{[i.get('index') for i in indices]}")
            check(f"{key}-索引", all(i.get("storeSizeHuman") for i in indices),
                  "索引存储大小可读文本非空")
            check(f"{key}-时间", bool(data.get("collectedAt")), f"collectedAt={data.get('collectedAt')}")
        else:
            check(f"{key}-降级", data["available"] is False, "available=false（不可达降级）")
            check(f"{key}-降级", bool(data.get("reason")), f"reason={data.get('reason')}")
            check(f"{key}-降级", data.get("reasonCode") is not None,
                  f"reasonCode={data.get('reasonCode')}")
            check(f"{key}-降级", data.get("fallback") is True,
                  "已回落 simple（配置 es-java 但未生效）")
            check(f"{key}-降级", data.get("implementation") == "simple",
                  f"implementation={data.get('implementation')}")
            # 降级时仍要能给出「配置值是什么」，否则运维无从下手
            check(f"{key}-降级", data.get("configuredType") == "es-java",
                  f"configuredType={data.get('configuredType')}")
    finally:
        stop_server(proc, fh)


def check_menu():
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, parent_id, menu_name, path, component, perms FROM sys_menu WHERE id = 1240")
            row = cur.fetchone()
    finally:
        conn.close()
    check("菜单", row is not None, f"sys_menu 存在 ES 监控菜单：{row}")
    if row:
        check("菜单", row[1] == 1200 and row[3] == "es" and row[4] == "monitor/es/index"
              and row[5] == "monitor:es:list",
              f"挂 1200「系统监控」下且路径/组件/权限码正确：parent={row[1]} path={row[3]} "
              f"component={row[4]} perms={row[5]}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", help="只跑指定场景（A / B / C）")
    args = parser.parse_args()

    if not os.path.exists(JAR):
        log("FATAL", f"fat jar 不存在：{JAR}（先 mvn clean package -pl pivotos-admin-server -DskipTests）")
        return 2

    keys = [args.only.upper()] if args.only else ["A", "B", "C"]
    failed = False
    try:
        for key in keys:
            run_scenario(key, SCENARIOS[key])
        check_menu()
    except AssertionError as e:
        failed = True
        log("FATAL", str(e))
    except Exception as e:
        failed = True
        log("FATAL", f"异常：{type(e).__name__}: {e}")

    passed = sum(1 for _, ok in results if ok)
    total = len(results)
    log("SUMMARY", f"{passed}/{total} PASS")
    for tag, ok in results:
        if not ok:
            log("FAILED", tag)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
