#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S123 FE-COMP 三件套：演示页菜单与动态路由 E2E（Flyway V1.2.52）。

为什么需要这支脚本：三个演示页路由由后端 `GET /system/menu/routers` 下发，
「菜单没插 / component 路径写错 / 文件没建」三种失败在前端长得一模一样——都只是
**点了菜单没内容或跳 404**，且后端完全不报错。故在这里把四个环节一次性钉死：

  A. Flyway V1.2.52 已应用且 success（防止「迁移没打包进 jar」）
  B. sys_menu 四条行（1180 目录 + 1181~1183 页面）字段逐项对账
     —— 尤其是 component 必须与 ui 仓的真实文件一一对应；
  C. 登录后 `/system/menu/routers` 树里能走到 //tool/demo/<xxx>，且 component 与 B 一致；
  D. 匿名访问被拦截（权限码 tool:demo:list 生效，不因为「演示页」就放开）；
  E. 反向对账：ui 仓三个演示页文件确实存在（防止改了库忘了建文件）。

端口与进程口径（2026-09-28 确立）：已有人在跑 8080 就复用、不抢也不 kill；
本脚本自己拉起的 jar 才在退出时 kill，只 kill 本轮记录的 PID。

用法：
    python3 scripts/e2e/fe_comp_menu_test.py
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

import pymysql

BASE = "http://localhost:8080"
LOGIN_URL = f"{BASE}/system/auth/login"
ROUTERS_URL = f"{BASE}/system/menu/routers"
SSE_URL = f"{BASE}/sse"

JAVA = "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java"
JAR = os.path.join(os.path.dirname(__file__), "..", "..", "pivotos-admin-server", "target",
                   "pivotos-admin-server.jar")
LOG_FILE = "/tmp/pivotos-fe-comp-menu-e2e.log"

MYSQL = dict(host="175.24.176.176", port=3306, user="root", password="mysql_DNCi3f",
             database="pivotos_dev", charset="utf8mb4")

UI_ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "..", "pivotos-ui")
VIEWS_ROOT = os.path.join(UI_ROOT, "apps", "admin", "src", "views")

# (id, parent_id, menu_name, menu_type, path, component, perms)
EXPECTED_MENUS = [
    (1180, 1100, "前端组件演示", "M", "demo", "", ""),
    (1181, 1180, "虚拟表格", "C", "virtual-table", "tool/demo/virtual-table/index", "tool:demo:list"),
    (1182, 1180, "动态表单", "C", "schema-form", "tool/demo/schema-form/index", "tool:demo:list"),
    (1183, 1180, "导入预览", "C", "import-preview", "tool/demo/import-preview/index", "tool:demo:list"),
]

PASSED = 0
FAILED = 0
OWNED_PID = None


def check(name, ok, detail=""):
    global PASSED, FAILED
    if ok:
        PASSED += 1
        print(f"  PASS  {name}")
    else:
        FAILED += 1
        print(f"  FAIL  {name}{(' —— ' + detail) if detail else ''}")


def section(title):
    print(f"\n【{title}】")


def http_json(url, token=None, timeout=30):
    req = urllib.request.Request(url, headers={"Authorization": token} if token else {})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def http_code(url, token=None, timeout=30):
    try:
        req = urllib.request.Request(url, headers={"Authorization": token} if token else {})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


def wait_ready(timeout=180):
    """等服务可用：返回 True 表示本来就跑着或在等待期间起来了"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(f"{BASE}/actuator/health", timeout=3)
            return True
        except urllib.error.HTTPError:
            return True
        except Exception:
            time.sleep(2)
    return False


def start_if_needed():
    """8080 没人跑就自己拉一个，并记录 PID（退出时只 kill 这个）"""
    global OWNED_PID
    try:
        urllib.request.urlopen(f"{BASE}/actuator/health", timeout=3)
        print("· 8080 已有服务在运行，本脚本复用，不做任何 kill")
        return True
    except urllib.error.HTTPError:
        print("· 8080 已有服务在运行（health 非 200），本脚本复用")
        return True
    except Exception:
        pass

    print("· 8080 无服务，本脚本自行拉起 fat jar（PID 计入善后清单）")
    env = dict(os.environ, TESSDATA_PREFIX="/opt/homebrew/share/tessdata")
    log = open(LOG_FILE, "w")
    proc = __import__("subprocess").Popen(
        [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
         "-jar", JAR, "--spring.profiles.active=dev"],
        stdout=log, stderr=log, env=env,
    )
    OWNED_PID = proc.pid
    return wait_ready()


def stop_if_owned():
    if not OWNED_PID:
        return
    try:
        os.kill(OWNED_PID, 15)
        print(f"\n· 已停止本轮自启进程 PID={OWNED_PID}")
    except Exception as e:  # 已退出也算结束
        print(f"\n· 本轮自启进程 PID={OWNED_PID} 已退出（{e}）")


def login(token_only=True):
    req = urllib.request.Request(
        LOGIN_URL,
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode())
    if body.get("code") != 0:
        raise RuntimeError(f"登录失败：{body}")
    data = body["data"]
    return data["token"] if isinstance(data, dict) else data


def walk(nodes, prefix=""):
    """路由树展平为 (full_path, component, name)"""
    for node in nodes or []:
        path = prefix + "/" + str(node.get("path"))
        yield path, node.get("component"), node.get("name")
        yield from walk(node.get("children") or [], path)


def main():
    if not start_if_needed():
        print("FATAL：8080 服务未能就绪")
        return 2

    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()

    section("A. Flyway V1.2.52 已应用")
    cur.execute("SELECT version, description, success, installed_on FROM flyway_schema_history "
                "WHERE version = '1.2.52'")
    row = cur.fetchone()
    check("迁移记录存在且 success", row is not None and row[2] == 1, str(row))
    if row:
        check("迁移描述包含 fe component demo menu", "fe component demo menu" in (row[1] or ""), row[1])

    section("B. sys_menu 四条行逐项对账")
    cur.execute("SELECT id, parent_id, menu_name, menu_type, path, component, perms FROM sys_menu "
                "WHERE id IN (1180, 1181, 1182, 1183)")
    actual = {r[0]: r for r in cur.fetchall()}
    for expected in EXPECTED_MENUS:
        got = actual.get(expected[0])
        check(f"菜单 {expected[0]} {expected[2]} 存在且字段一致", got == expected,
              f"期望={expected} 实际={got}")

    section("C. 前端演示页文件存在（防止改了库忘了建文件）")
    for menu in EXPECTED_MENUS:
        component = menu[5]
        if not component:
            continue
        file_path = os.path.join(VIEWS_ROOT, *component.split("/")) + ".vue"
        ok = os.path.isfile(file_path)
        check(f"存在 {os.path.relpath(file_path, UI_ROOT)}", ok, file_path)

    section("D. 登录后动态路由下发 //tool/demo/*")
    token = login()
    body = http_json(ROUTERS_URL, token)
    check("routers 接口 code=0", body.get("code") == 0, json.dumps(body)[:200])
    rows = list(walk(body.get("data") or []))
    for menu in EXPECTED_MENUS[1:]:
        target = f"//tool/demo/{menu[4]}"
        hit = [r for r in rows if r[0] == target]
        check(f"路由 {target} 存在", len(hit) == 1, str([r[0] for r in rows if 'demo' in r[0]]))
        if hit:
            check(f"{target} 的 component 指向 {menu[5]}", hit[0][1] == menu[5], str(hit[0]))
    demo_dir = [r for r in rows if r[0] == "//tool/demo"]
    check("目录节点 //tool/demo 挂 Layout", demo_dir and demo_dir[0][1] == "Layout", str(demo_dir))
    check("目录节点 //tool/demo 下挂 3 个子路由", len([r for r in rows if r[0].startswith("//tool/demo/")]) == 3)

    section("E. 鉴权：匿名访问拿不到菜单")
    # 口径说明：本项目「未登录」在 REST 上的既有形态是 HTTP 200 + code=1002（RO 统一返回体），
    # 只有 /sse 这类非 REST 端点才直接回 401。两种都要认，**别把「必须 401」当唯一正确形态**。
    anon = http_json(ROUTERS_URL)
    check("匿名 routers 返回 code=1002（未认证）", anon.get("code") == 1002, json.dumps(anon)[:160])
    check("匿名 routers 不下发 data", anon.get("data") in (None, [], {}), json.dumps(anon.get("data"))[:160])
    code = http_code(SSE_URL)
    check("匿名 /sse 返回 401/403（既有基线）", code in (401, 403), f"HTTP {code}")

    conn.close()
    print(f"\n合计：{PASSED} PASS / {FAILED} FAIL")
    return 0 if FAILED == 0 else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        stop_if_owned()
