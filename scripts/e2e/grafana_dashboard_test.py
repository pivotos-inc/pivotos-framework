#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Grafana 面板 JSON 校验 + 表达式真机可解析性验证（V3.1-H · B5-遗留清偿）。

为什么要这个脚本
----------------
面板 JSON 是最容易**看起来完全正确、导入后全是「No data」**的资产：
写在 JSON 里的 PromQL 没人盯着改，指标名一旦被重构（比如 hibern 改 Druid、
http.server.requests 改了 histogram 后缀），面板不会报错，只会静静地什么都不显示。
于是「我们有监控」这句话是假的，但没人会发现。

所以这里做两层检查：

  1. **结构层**（不需要起服务）：JSON 合法、uid/title/panels 齐备、panel id 不重复、
     每个 target 都有 expr 与 datasource、时间区间与刷新率合理。
  2. **表达式层**（需要起 fat jar）：真的把应用起来、制造流量、抓一次
     `/actuator/prometheus`，把面板里每条 expr 引用的**指标名**取出来，
     逐个确认在真实抓取结果里存在。找不到的必须落在显式白名单里并给出理由，
     否则直接 FAIL —— 宁可红，也不要一个「导入后没数据」的面板。

用法：
    python3 scripts/e2e/grafana_dashboard_test.py                # 结构 + 起服实抓
    python3 scripts/e2e/grafana_dashboard_test.py --skip-server  # 只做结构层
"""
from __future__ import annotations

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
ROOT = "/".join(os.path.abspath(__file__).split("/")[:-3])
JAR = f"{ROOT}/pivotos-admin-server/target/pivotos-admin-server.jar"
DASHBOARD_DIR = f"{ROOT}/scripts/ops/grafana"
WORK_ROOT = "/tmp/pivotos-v31h-grafana"
PROM_PATH = "/actuator/prometheus"

results: list[tuple[str, bool]] = []


def log(tag: str, msg: str) -> None:
    print(f"[{tag}] {msg}", flush=True)


def check(tag: str, cond: bool, msg: str) -> None:
    results.append((tag, bool(cond)))
    if cond:
        log("PASS", f"{tag} {msg}")
    else:
        log("FAIL", f"{tag} {msg}")
        raise AssertionError(f"{tag} 断言失败：{msg}")


# PromQL 里这些不是指标名：函数、关键字、聚合修饰符
PROMQL_KEYWORDS = {
    "sum", "by", "on", "without", "rate", "irate", "increase", "histogram_quantile",
    "avg", "min", "max", "count", "count_values", "stddev", "stdvar", "topk", "bottomk",
    "quantile", "abs", "ceil", "floor", "round", "clamp", "clamp_max", "clamp_min",
    "deriv", "predict_linear", "holt_winters", "changes", "resets", "delta", "idelta",
    "label_replace", "label_join", "vector", "scalar", "time", "absent", "sort", "sort_desc",
    "group_left", "group_right", "and", "or", "unless", "ignoring", "offset", "bool",
    "le", "inf", "nan", "s", "m", "h", "d", "w", "y", "__all",
}

# 没机会抓到、但确实存在的指标：给出**可验证的理由**才允许进白名单。
LAZY_METRICS = {
    "pivotos_ai_calls_total":
        "MeteredChatModel 的埋点；需要真的打过一次 LLM 调用才会注册（本机无 LLM Key）",
    "pivotos_ai_latency_seconds_bucket":
        "同上，AI 延迟直方图；无 LLM Key 环境不会有数据",
    "pivotos_ai_tokens_total":
        "同上，AI token 计数；无 LLM Key 环境不会有数据",
    "pivotos_ai_latency_seconds_max":
        "AI 延迟 Timer 的窗口最大值，同 AI 系列一样需要真的打过 LLM 调用",
    "pivotos_ai_latency_seconds_sum": "AI 延迟 Timer 的累计值，同上需要 LLM 调用",
    "pivotos_ai_latency_seconds_count": "AI 延迟 Timer 的次数，同上需要 LLM 调用",
}


def load_dashboards() -> list[tuple[str, dict]]:
    files = sorted(glob.glob(os.path.join(DASHBOARD_DIR, "*.json")))
    return [(os.path.basename(f), json.load(open(f, encoding="utf-8"))) for f in files]


def check_structure() -> list[tuple[str, dict]]:
    dashboards = load_dashboards()
    check("G-1", len(dashboards) >= 1,
          f"至少有一份面板定义（实测 {len(dashboards)} 份：{[n for n, _ in dashboards]}）")

    for name, doc in dashboards:
        check("G-2", bool(doc.get("uid")) and bool(doc.get("title")),
              f"{name}：uid 与 title 齐备（uid={doc.get('uid')}）")
        panels = doc.get("panels") or []
        check("G-3", len(panels) >= 3, f"{name}：至少 3 个面板（实测 {len(panels)}）")

        ids = [p.get("id") for p in panels]
        dup = {i for i in ids if ids.count(i) > 1}
        check("G-4", not dup, f"{name}：面板 id 不得重复（重复项={dup}）")

        missing_expr, missing_ds, missing_title = [], [], []
        for panel in panels:
            if not (panel.get("title") or "").strip():
                missing_title.append(panel.get("id"))
            if not (panel.get("type") or "").strip():
                missing_title.append(panel.get("id"))
            targets = panel.get("targets") or []
            if not targets:
                missing_expr.append(f"{panel.get('id')}（无 target）")
            for target in targets:
                if not (target.get("expr") or "").strip():
                    missing_expr.append(str(panel.get("id")))
                if not target.get("datasource") and not panel.get("datasource"):
                    missing_ds.append(str(panel.get("id")))
        check("G-5", not missing_expr, f"{name}：每个 target 都要有 expr（缺={missing_expr}）")
        check("G-6", not missing_ds,
              f"{name}：每个面板都要声明 datasource（缺={missing_ds}，建议写 ${{DS_PROMETHEUS}} 变量）")
        check("G-7", not missing_title, f"{name}：每个面板都要有 title 与 type（缺={missing_title}）")

        # 全局变量：必须有 Prometheus 数据源变量，否则导入者要手工改每一处
        var_names = [v.get("name") for v in (doc.get("templating") or {}).get("list", [])]
        check("G-8", "DS_PROMETHEUS" in var_names,
              f"{name}：必须声明 DS_PROMETHEUS 数据源变量（实测={var_names}）")
    return dashboards


def metric_names_in_expr(expr: str) -> list[str]:
    """从 PromQL 里抽出可能是指标名的标识符。

    刻意不考虑「紧跟 `{`」之外的判别规则：面板里的写法五花八门，
    宁可多抽几个候选（多数是标签名或变量），由后续比对 Prometheus 的实际指标表来兜。
    """
    # 去掉 {{label}} 这类 Grafana 变量与字符串字面量，避免误抽
    cleaned = re.sub(r"\{\{[^}]*\}\}", " ", expr)
    cleaned = re.sub(r"`[^`]*`", " ", cleaned)
    cleaned = re.sub(r'"[^"]*"', " ", cleaned)
    candidates = re.findall(r"[a-zA-Z_:][a-zA-Z0-9_:]*", cleaned)
    return [c for c in candidates
            if c.lower() not in PROMQL_KEYWORDS and not c.startswith("__")
            and c not in {"application", "level", "outcome", "status", "uri", "method",
                          "exception", "error", "pool", "type", "operation", "pattern",
                          "provider", "model", "cause", "id", "area"}]


def scrape_actual_metrics() -> set[str]:
    """起服 → 制造流量 → 抓 Prometheus → 停服，返回实际出现的指标名。"""
    if os.path.exists(WORK_ROOT):
        shutil.rmtree(WORK_ROOT)
    os.makedirs(WORK_ROOT, exist_ok=True)

    env = {k: v for k, v in os.environ.items() if not k.startswith("PIVOTOS_")}
    env.update({
        "TESSDATA_PREFIX": "/opt/homebrew/share/tessdata",
        # 慢接口阈值 0 = 全部计入，否则亚毫秒请求一条都抓不到（V3.1-F 踩过）
        "PIVOTOS_METRICS_SLOW_REQUEST_THRESHOLD_MS": "0",
    })
    log_path = os.path.join(WORK_ROOT, "console.log")
    fh = open(log_path, "wb")
    proc = subprocess.Popen(
        [JAVA, "-Djna.library.path=/opt/homebrew/lib", "-Djava.awt.headless=true",
         "-jar", JAR, "--spring.profiles.active=dev"],
        stdout=fh, stderr=subprocess.STDOUT, env=env, cwd=WORK_ROOT)

    try:
        deadline = time.time() + 240
        while time.time() < deadline:
            if proc.poll() is not None:
                raise RuntimeError(f"后端提前退出（exit={proc.returncode}），见 {log_path}")
            try:
                with urllib.request.urlopen(BASE + "/health/live", timeout=5) as r:
                    if r.status == 200:
                        break
            except Exception:  # noqa: BLE001
                pass
            time.sleep(2)
        else:
            raise RuntimeError("后端 240s 未就绪")

        # 制造覆盖性的流量：登录 + 几个有代表性的端点，让懒注册指标出现
        body = json.dumps({"username": "admin", "password": "admin123"}).encode()
        req = urllib.request.Request(BASE + "/system/auth/login", data=body,
                                     headers={"Content-Type": "application/json"})
        token = None
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                token = json.loads(resp.read())["data"]["token"]
        except Exception as exc:  # noqa: BLE001
            log("WARN", f"登录失败，只能抓到匿名可达的指标：{exc}")
        for path in ("/system/menu/routers", "/system/user/page", "/health/live",
                     "/health/ready", "/actuator/prometheus", "/health/not-exists"):
            try:
                headers = {"Authorization": token} if token and path.startswith("/system") else {}
                urllib.request.urlopen(
                    urllib.request.Request(BASE + path, headers=headers), timeout=15).read()
            except Exception:  # noqa: BLE001
                pass
        time.sleep(3)

        with urllib.request.urlopen(BASE + PROM_PATH, timeout=20) as resp:
            text = resp.read().decode("utf-8", errors="replace")

        names = set()
        for line in text.splitlines():
            if line.startswith("# ") or not line.strip():
                continue
            head = line.split("{")[0].strip()
            if head:
                names.add(head)
                names.add(re.sub(r"(_count|_sum|_max|_bucket|_total)$", "", head))
        log("INFO", f"本次实抓到 {len(names)} 个指标名（含去后缀形式）")
        return names
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.send_signal(signal.SIGKILL)
            proc.wait(timeout=10)
        fh.close()


def port_busy(port: int = 8080) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def check_expressions(dashboards: list[tuple[str, dict]], actual: set[str]) -> None:
    unresolved: list[str] = []
    checked = 0
    for name, doc in dashboards:
        for panel in doc.get("panels", []):
            for target in panel.get("targets", []):
                expr = target.get("expr", "")
                candidates = metric_names_in_expr(expr)
                if not candidates:
                    continue
                checked += 1
                hits = [c for c in candidates if c in actual or f"{c}_total" in actual]
                if not hits:
                    unresolved.append(f"{name} panel#{panel.get('id')} → {candidates} :: {expr[:70]}")
    check("G-9", checked > 0, f"共校验 {checked} 条 target 表达式")

    unknown = []
    for item in unresolved:
        reason = next((why for metric, why in LAZY_METRICS.items() if metric in item), None)
        if reason is None:
            unknown.append(item)
        else:
            log("INFO", f"白名单放行（{reason}）：{item.split('::')[0].strip()}")
    check("G-10", not unknown,
          f"不得引用 Prometheus 里找不到的指标（未落白名单的 {len(unknown)} 条）：{unknown}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-server", action="store_true", help="只做结构层校验")
    args = parser.parse_args()

    print("=" * 78)
    print("Grafana 面板校验（结构 + 表达式真机可解析性）")
    print("=" * 78)

    failed = 0
    try:
        dashboards = check_structure()
    except AssertionError as exc:
        log("ERROR", str(exc))
        return 1

    if args.skip_server:
        log("INFO", "--skip-server：跳过表达式层")
    else:
        if port_busy():
            log("ERROR", "8080 已被占用，请确认归属后再跑（本脚本只 kill 自己拉起的进程）")
            return 1
        if not os.path.exists(JAR):
            log("ERROR", f"缺少 fat jar：{JAR}（先 mvn clean package）")
            return 1
        try:
            actual = scrape_actual_metrics()
            check_expressions(dashboards, actual)
        except (AssertionError, RuntimeError, urllib.error.URLError) as exc:
            log("ERROR", f"表达式层校验失败：{exc}")
            failed += 1
        finally:
            if port_busy():
                log("WARN", "8080 仍被占用，请人工确认归属")

    passed = sum(1 for _, ok in results if ok)
    print("-" * 78)
    print(f"{passed}/{len(results)} 通过，失败 {failed} 个")
    return 1 if failed or passed != len(results) else 0


if __name__ == "__main__":
    sys.exit(main())
