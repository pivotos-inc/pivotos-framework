#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PivotOS 进程托管资产静态校验（V3.1-G · P1-B3 进程托管）。

为什么需要这个脚本
------------------
systemd unit、启停脚本、JVM 参数模板都是**文本文件**，它们有三个特点：

  1. 平时不在编译路径上 —— 写错一个指令，IDE 不红、CI 不红，只有部署到机器上才炸；
  2. 但一旦炸就是**启动不起来**或**停机丢请求**，发生在最不该出事的时候；
  3. 而且容易**多处漂移** —— 停机超时同时写在 application.yml、unit、stop-prod.sh 三处，
     改一处忘另外两处，就变成「优雅停机其实没等到」。

因此把「能静态判定的部分」全部变成断言，纳入回归：

  U 组  unit 文件关键指令（Type / ExecStart / KillSignal / Restart / 资源限制 / 安全加固）
  S 组  脚本语法（bash -n / py_compile）与 JVM 必带项
  C 组  跨文件一致性（停机超时三处成链）
  K 组  机密扫描（仓库内不得出现明文口令）

⚠️ 覆盖不到的部分要如实说明：本脚本**不**验证 systemd 真实行为（macOS 无 systemd，
   真机行为只能由部署方在生产机上验证），只保证文件本身自洽、且与代码约定一致。

退出码：0 = 全部通过；1 = 存在 FAIL。

用法：
    python3 scripts/ops/check_process_assets.py
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OPS_DIR = os.path.join(ROOT, "scripts", "ops")
ADMIN_RES = os.path.join(ROOT, "pivotos-admin-server", "src", "main", "resources")

UNIT = os.path.join(OPS_DIR, "pivotos-admin-server.service")
APP_YML = os.path.join(ADMIN_RES, "application.yml")
PROD_YML = os.path.join(ADMIN_RES, "application-prod.yml")
STOP_SH = os.path.join(OPS_DIR, "stop-prod.sh")
JVM_SH = os.path.join(OPS_DIR, "jvm-opts.sh")
PREFLIGHT = os.path.join(OPS_DIR, "preflight.py")

results: list[tuple[str, bool, str]] = []


def check(tag: str, cond: bool, msg: str) -> bool:
    results.append((tag, bool(cond), msg))
    print(("✅" if cond else "❌") + f" [{tag}] {msg}", flush=True)
    return bool(cond)


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def parse_unit(text: str) -> dict[str, list[str]]:
    """解析 systemd unit：忽略 # 注释，允许同名键出现多次（ExecStart 等）。"""
    out: dict[str, list[str]] = {}
    section = ""
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue
        if "=" not in line or section != "Service":
            continue
        key, _, value = line.partition("=")
        out.setdefault(key.strip(), []).append(value.strip())
    return out


def parse_duration(text: str) -> float | None:
    """30s / 2m / 500ms → 秒。"""
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(ms|s|m|h)?\s*", text or "")
    if not m:
        return None
    value = float(m.group(1))
    unit = m.group(2) or "s"
    return value * {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0}[unit]


def check_unit_file() -> None:
    if not check("U0", os.path.isfile(UNIT), "unit 模板存在：scripts/ops/pivotos-admin-server.service"):
        return
    text = read(UNIT)
    for section in ("Unit", "Service", "Install"):
        check(f"U1-{section}", f"[{section}]" in text, f"unit 含 [{section}] 段")
    svc = parse_unit(text)

    check("U2", svc.get("Type", [""])[0] == "simple",
          "Type=simple（配合 run.sh 的 exec java，systemd 信号/退出码语义才正确）")
    check("U3", any(v.endswith("/run.sh") for v in svc.get("ExecStart", [])),
          "ExecStart 指向 run.sh（前台 exec，避免中间 shell 吃掉信号）")
    check("U4", svc.get("KillSignal", [""])[0] == "SIGTERM",
          "KillSignal=SIGTERM（先让 Spring 走 graceful，而不是直接 -9）")

    timeout = parse_duration(svc.get("TimeoutStopSec", [""])[0])
    check("U5", timeout is not None and timeout > 0,
          f"TimeoutStopSec 可解析：{svc.get('TimeoutStopSec', [''])[0]}")

    restart = svc.get("Restart", [""])[0]
    check("U6", restart == "on-failure",
          f"Restart=on-failure（当前 {restart}；配 always 会让手工 stop 被重新拉起）")

    check("U7", bool(svc.get("LimitNOFILE")), "LimitNOFILE 已设置（默认 1024 会在并发下撞句柄上限）")

    check("U8", svc.get("ProtectSystem", [""])[0] == "strict" and bool(svc.get("ReadWritePaths")),
          "ProtectSystem=strict 且 ReadWritePaths 非空（否则日志目录不可写，启动即失败）")

    pre = svc.get("ExecStartPre", [])
    check("U9", any("preflight.py" in v for v in pre),
          "ExecStartPre 调用了 preflight.py（端口/环境变量/包/JDK 前置拦截）")

    # EnvironmentFile 必须在仓库外，否则「填了值的 env 文件」极易被误提交
    env_files = svc.get("EnvironmentFile", [])
    check("U10", bool(env_files), "EnvironmentFile 已声明")
    leaked = [p for p in env_files if os.path.abspath(p.lstrip("-")).startswith(ROOT + os.sep)]
    check("U11", not leaked, f"EnvironmentFile 不得指向仓库内（当前 {env_files}）")

    # 机密扫描：unit 里出现「键=非空且非占位符」即视为明文口令
    secret_re = re.compile(r"(?i)\b(password|passwd|secret|token|api[-_]?key)\s*=\s*(.+)$")
    hits = [m.group(0) for m in (secret_re.search(ln) for ln in text.splitlines())
            if m and not m.group(2).strip().startswith("${") and m.group(2).strip()]
    check("U12", not hits, f"unit 文件不得含明文口令（命中 {hits}）")

    # 停机超时成链：appGrace <= stopTerm < systemdTimeout
    app_grace = parse_duration(re.search(
        r"timeout-per-shutdown-phase:\s*([0-9]+(?:\.\d+)?(?:ms|s|m|h)?)", read(APP_YML)
    ).group(1)) if re.search(r"timeout-per-shutdown-phase:\s*([0-9]+(?:\.\d+)?(?:ms|s|m|h)?)",
                    read(APP_YML)) else None
    stop_term = re.search(r'TERM_TIMEOUT_SECONDS="\$\{TERM_TIMEOUT_SECONDS:-(\d+)\}"', read(STOP_SH))
    stop_term = float(stop_term.group(1)) if stop_term else None

    check("C1", app_grace is not None and app_grace > 0,
          f"application.yml 已配置停机宽限期：{app_grace}s")
    check("C2", stop_term is not None and app_grace is not None and stop_term >= app_grace,
          f"stop-prod.sh 的 TERM 等待({stop_term}s) 必须 ≥ 应用宽限期({app_grace}s)，"
          f"否则 SIGKILL 会早于宽限期结束")
    check("C3", timeout is not None and stop_term is not None and timeout > stop_term,
          f"unit 的 TimeoutStopSec({timeout}s) 必须 > stop-prod.sh 的 TERM 等待({stop_term}s)，"
          f"否则 systemd 会先一步 SIGKILL")


def check_scripts() -> None:
    shells = sorted(f for f in os.listdir(OPS_DIR) if f.endswith(".sh"))
    check("S0", bool(shells), f"发现 {len(shells)} 个 shell 脚本：{shells}")

    if shutil.which("bash"):
        for name in shells:
            proc = subprocess.run(["bash", "-n", os.path.join(OPS_DIR, name)],
                                  capture_output=True, text=True)
            check(f"S1-{name}", proc.returncode == 0,
                  f"bash -n {name}" + ("" if proc.returncode == 0 else f" → {proc.stderr.strip()}"))
    else:
        print("⚠️  未找到 bash，跳过语法检查", flush=True)

    jvm = read(JVM_SH)
    check("S2-headless", "-Djava.awt.headless=true" in jvm,
          "jvm-opts.sh 必带 -Djava.awt.headless=true（否则 OCR/上传报 1500）")
    check("S3-jna", "-Djna.library.path" in jvm, "jvm-opts.sh 必带 -Djna.library.path")
    check("S4-dump", "-XX:+HeapDumpOnOutOfMemoryError" in jvm, "jvm-opts.sh 必带 OOM 堆转储")
    check("S5-gc", "-Xlog:gc" in jvm, "jvm-opts.sh 必带 GC 日志")

    proc = subprocess.run([sys.executable, "-m", "py_compile", PREFLIGHT],
                          capture_output=True, text=True)
    check("S6-preflight", proc.returncode == 0, "preflight.py 语法可编译")

    # 变量名后紧跟全角字符：macOS bash 3.2 在 UTF-8 locale 下会把 `）` 当成变量名的一部分
    # （实测 `echo "（$PROFILE）"` → `PROFILE\ufffd: unbound variable`），Linux bash 5 却不会。
    # 同一份脚本在两个平台表现不同，属于最难排查的一类问题，故用花括号统一规避。
    bad_var = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*[^\x00-\x7F]")
    for name in shells:
        hits = [f"{i}:{ln.strip()}" for i, ln in
                enumerate(read(os.path.join(OPS_DIR, name)).splitlines(), 1)
                if bad_var.search(ln)]
        check(f"S8-{name}", not hits,
              f"shell 变量名后不得紧跟非 ASCII 字符（用 ${{VAR}}）" + ("" if not hits else f" → {hits}"))

    stop = read(STOP_SH)
    check("S7-stop-order", "kill -TERM" in stop and "kill -KILL" in stop,
          "stop-prod.sh 先 TERM 再 KILL（不搞 kill -9 一把梭）")


def check_graceful_config() -> None:
    """优雅停机：Boot 4 默认已是 graceful，这里固化 + 校验与 unit/stop 脚本成链。"""
    m = re.search(r"shutdown:\s*(\w+)", read(APP_YML))
    check("G1", m is not None and m.group(1) == "graceful",
          f"server.shutdown=graceful（当前 {m.group(1) if m else '未配置'}）"
          + " —— Boot 4.1.1 默认值本就是 graceful，此处显式固化防上游回退")


def check_druid_keys() -> None:
    """连接池参数 key 白名单：出现在 yml 里的 druid 键必须是 DruidConfig 认得的。"""
    text = read(APP_YML)
    block = re.search(r"^\s{6}druid:\s*\n((?:^\s{8}.*\n|^\s*$)*)", text, re.M)
    if not check("D0", block is not None, "application.yml 中定位到 spring.datasource.dynamic.druid 段"):
        return
    body = block.group(1)
    # 只取「叶子键」（下一层缩进超过当前层，说明是子映射，跳过）
    known = {
        # 标量项（直接绑到 DruidConfig 的同名字段）
        "max-wait", "validation-query", "validation-query-timeout", "test-while-idle",
        "test-on-borrow", "test-on-return", "time-between-eviction-runs-millis",
        "min-evictable-idle-time-millis", "max-evictable-idle-time-millis",
        "keep-alive", "keep-alive-between-time-millis", "remove-abandoned",
        "remove-abandoned-timeout-millis", "log-abandoned", "filters",
        "stat-sql-max-size", "connect-timeout", "socket-timeout",
        "initial-size", "min-idle", "max-active",
        # 子映射项（由 DruidStatConfigUtil / DruidWallConfigUtil 等转 setter）
        "stat", "wall", "slf4j", "log4j", "log4j2", "commons-log", "proxy-filters",
    }
    unknown = set()
    for line in body.splitlines():
        m = re.match(r"^(\s*)([a-zA-Z][\w-]*)\s*:", line)
        if not m:
            continue
        indent, key = len(m.group(1)), m.group(2)
        # 8 空格 = druid 的直接子项；更深的是 stat 等子映射，由具体断言覆盖
        if indent == 8 and key not in known:
            unknown.add(key)
    check("D1", not unknown, f"druid 段不得出现 DruidConfig 不认得的键（命中 {sorted(unknown)}）")

    # 反 Hikari 误用：本项目是 Druid，yml 里出现 hikari 配置即为错配
    hikari = [ln.strip() for ln in body.splitlines() if re.search(r"(?i)\bhikari\b", ln)]
    check("D2", not hikari, f"druid 段不得混入 hikari 配置（命中 {hikari}）")


def main() -> int:
    check_unit_file()
    check_scripts()
    check_graceful_config()
    check_druid_keys()

    passed = sum(1 for _, ok, _ in results if ok)
    failed = len(results) - passed
    print("-" * 78)
    print(f"合计：PASS {passed} · FAIL {failed}")
    if failed:
        print("❌ 进程托管资产存在不合规项。")
        return 1
    print("✅ 进程托管资产静态校验全部通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
