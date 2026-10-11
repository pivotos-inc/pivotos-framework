#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PivotOS 启动前置检查（V3.1-G · P1-B3 进程托管）。

为什么要有这个脚本
------------------
以前「启动前置条件不满足」全靠启动失败才暴露，而且暴露得**晚且难归因**：

  · 端口被别的进程占了   → 到 WebServer 阶段才 BindException，日志一屏红，
                          此时 JVM 已起、Flyway 可能已跑，现场比问题本身复杂
  · 数据库不可达/账号错  → Flyway 校验阶段才抛，应用处于「半起」态
  · 必需环境变量没注入   → 生产连着开发库跑起来了（**最危险：不报错、数据写错地方**）

所以把「JVM 还没起来之前就能判定」的事一次性检查完，并给出可执行的修复提示。
这也是 A1（凭据外置）的配套：凭据外置之后「没注入」成了常态故障，必须有门禁。

严重度分级（**刻意**不是一刀切）
--------------------------------
  ERROR  阻断启动。不满足时继续启动**必然**失败或**必然**出错：
        必需环境变量缺失、端口被占用、jar 不存在、JDK 版本不够。
  WARN   只告警不阻断。不满足时应用仍能正常提供**大部分**能力：
        数据源连不上、TESSDATA_PREFIX 没配（只影响 OCR）、堆上限没显式设置。

  ⚠️ 数据源连通为什么默认只 WARN：改成阻断的话，一次网络抖动或一个库短暂不可达
     就会让整台机器**起不来**，故障恢复时反而更难排查——「起来了但 /health/ready=DOWN」
     比「起不来」好定位得多（ ready 端点会把原因写进 details ）。要硬阻断用 --strict。

退出码
------
  0  没有 ERROR（允许有 WARN）
  1  存在 ERROR，或 --strict 且存在 WARN
  2  脚本自身的用法/依赖错误

用法
----
    python3 scripts/ops/preflight.py                    # 默认 prod
    python3 scripts/ops/preflight.py --profile=dev
    python3 scripts/ops/preflight.py --port=8081 --strict
    python3 scripts/ops/preflight.py --skip-db          # 离线环境跳过库连通
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import socket
import subprocess
import sys
from urllib.parse import unquote, urlparse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# Boot 4 基线要求 JDK 17+（本项目实跑 JDK 25）
MIN_JDK_MAJOR = 17

# 每个 profile 的「必需环境变量」。
#   prod：A1 之后连接串与凭据**没有**兜底值，缺一个就会连错环境，必须硬拦。
#   test/dev：profile yml 里刻意保留了字面量兜底（否则 1014 条 IT 与本地开发全断），
#             所以这里**不强制**注入，只打 INFO 说明现状。
REQUIRED_BY_PROFILE = {
    "prod": [
        ("PIVOTOS_MYSQL_URL", "JDBC 连接串；空默认会让数据源装配直接失败（A1 的 fail-fast 设计）"),
        ("PIVOTOS_MYSQL_USERNAME", "MySQL 账号；生产应为最小权限应用账号"),
        ("PIVOTOS_MYSQL_PASSWORD", "MySQL 口令；由密钥管理系统或部署平台注入"),
        ("PIVOTOS_REDIS_HOST", "Redis 主机"),
        ("PIVOTOS_REDIS_PASSWORD", "Redis 口令"),
        ("PIVOTOS_FIELD_ENCRYPT_KEY",
         "字段加密 AES 密钥（16/24/32 位）；prod 若解析到 git 里的开发默认值，应用会拒绝启动"),
    ],
    "test": [],
    "dev": [],
}

LEVEL_ORDER = {"PASS": 0, "WARN": 1, "FAIL": 2}


class Finding:
    """一条检查结论。hint 是「下一步该干什么」，不是复述问题。"""

    def __init__(self, level: str, name: str, detail: str = "", hint: str = "") -> None:
        self.level = level
        self.name = name
        self.detail = detail
        self.hint = hint


def env(name: str) -> str:
    return (os.getenv(name) or "").strip()


VALID_KEY_LENGTHS = (16, 24, 32)


def check_field_encrypt_key(findings: list[Finding], profile: str) -> None:
    """字段加密密钥长度校验（原在 start-prod.sh，收敛到此处统一口径）。

    密钥一旦写入数据就**不可更换**，否则已加密字段（如 ai_api_key.api_key）无法解密；
    prod 形态下应用侧 FieldEncryptKeyGuard 会在解析到开发默认值时直接拒绝启动，
    这里提前拦一次，报错比启动到一半抛 IllegalStateException 好读。
    """
    key = env("PIVOTOS_FIELD_ENCRYPT_KEY")
    if not key:
        if profile == "prod":
            # 缺失本身已由 check_required_env 判 FAIL，这里不重复计
            return
        findings.append(Finding(
            "WARN", "字段加密密钥",
            f"profile={profile} 未注入 PIVOTOS_FIELD_ENCRYPT_KEY，将使用 profile 兜底值",
            "仅可用于开发/测试；生产必须显式注入，且值不可等于 git 里的开发默认密钥",
        ))
        return
    if len(key) not in VALID_KEY_LENGTHS:
        findings.append(Finding(
            "FAIL", "字段加密密钥",
            f"长度 {len(key)} 位，必须为 16 / 24 / 32 位（AES-128/192/256）",
            "长度不对会在首次加密时抛异常，且**已写入的数据不可回滚**",
        ))
        return
    findings.append(Finding("PASS", "字段加密密钥", f"长度 {len(key)} 位，合法"))


def check_required_env(findings: list[Finding], profile: str) -> None:
    required = REQUIRED_BY_PROFILE.get(profile, [])
    if not required:
        findings.append(Finding(
            "PASS", "必需环境变量",
            f"profile={profile} 不强制注入（凭据由 application-{profile}.yml 兜底）",
        ))
        return
    missing = []
    for name, why in required:
        if not env(name):
            missing.append(f"{name}（{why}）")
    if missing:
        findings.append(Finding(
            "FAIL", "必需环境变量",
            f"profile={profile} 缺少 {len(missing)} 项",
            "凭据一律由部署环境注入，禁止写进脚本或 yml；缺失项：\n" +
            "\n".join(f"          - {m}" for m in missing),
        ))
        return
    findings.append(Finding(
        "PASS", "必需环境变量",
        f"profile={profile} 的 {len(required)} 项均已注入",
    ))


def listener_lines(port: int) -> list[str] | None:
    """查端口监听者，返回去掉表头后的原始行。一个工具都没有时返回 None（交由调用方降级）。"""
    if shutil.which("lsof"):
        try:
            out = subprocess.run(
                ["lsof", "-Pan", f"-i:{port}", "-sTCP:LISTEN"],
                capture_output=True, text=True, timeout=10,
            ).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return None
        return out.splitlines()[1:]
    if shutil.which("ss"):
        try:
            out = subprocess.run(
                ["ss", "-ltnH", f"sport = :{port}"],
                capture_output=True, text=True, timeout=10,
            ).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return None
        return [ln for ln in out.splitlines() if ln.strip()]
    return None


def can_bind(port: int) -> tuple[bool, str]:
    """降级方案：真绑一次判空闲。

    ⚠️ 不能当首选：BSD/macOS 的 SO_REUSEADDR 语义是「已绑的都设了 REUSEADDR 就允许再绑」，
    探测会**误判为空闲**（实测：占用方带 SO_REUSEADDR 时 bind 直接成功）。因此只在
    lsof / ss 都不可用时才用它。这里刻意不设 SO_REUSEADDR，避免主动放大误判面。
    """
    err = ""
    for family, addr in ((socket.AF_INET, "0.0.0.0"), (socket.AF_INET6, "::")):
        s = None
        try:
            s = socket.socket(family, socket.SOCK_STREAM)
            s.bind((addr, port))
            return True, ""
        except OSError as e:
            err = f"{e.__class__.__name__}: {e}"
        finally:
            if s is not None:
                s.close()
    return False, err or "端口不可绑定"


def describe_listener(port: int) -> str:
    """报告占用者（PID/进程名/监听地址）。查不到就如实说查不到，不猜。"""
    lines = listener_lines(port)
    if lines is None:
        return "（本机无 lsof / ss，无法判定归属）"
    if not lines:
        return "（未列出监听者，可能是瞬时占用或权限不足）"
    return " | ".join(" ".join(ln.split()) for ln in lines[:3])


def check_port(findings: list[Finding], port: int) -> None:
    """端口占用判定：首选 lsof -sTCP:LISTEN（能报归属），ss 次之，都没有才退化到 bind 探测。"""
    lines = listener_lines(port)
    if lines is None:
        ok, err = can_bind(port)
        if ok:
            findings.append(Finding(
                "PASS", "端口占用",
                f"{port} 空闲（无 lsof/ss，改用 bind 探测）"))
            return
        findings.append(Finding(
            "FAIL", "端口占用",
            f"{port} 不可绑定：{err}",
            f"无 lsof / ss 可判定归属；先人工确认 {port} 上的进程，不要盲目 kill。"))
        return

    if not lines:
        findings.append(Finding("PASS", "端口占用", f"{port} 空闲"))
        return

    findings.append(Finding(
        "FAIL", "端口占用",
        f"{port} 已被占用：{describe_listener(port)}",
        f"先用 lsof -Pan -i:{port} -sTCP:LISTEN 看清归属；属于他人进程时**不要自行 kill**，"
        f"改端口（--port）或报负责人拍板。",
    ))


def check_jar(findings: list[Finding], jar: str) -> None:
    if not jar:
        findings.append(Finding("FAIL", "启动包", "未指定 jar 路径", "用 --jar 指定，或设置 APP_HOME/JAR_NAME"))
        return
    p = os.path.abspath(jar)
    if not os.path.isfile(p):
        findings.append(Finding("FAIL", "启动包", f"文件不存在：{p}", "先执行打包：mvn clean package -DskipTests"))
        return
    if not os.access(p, os.R_OK):
        findings.append(Finding("FAIL", "启动包", f"文件不可读：{p}", f"检查运行账号（unit 里的 User=）对 {p} 的权限"))
        return
    findings.append(Finding("PASS", "启动包", f"{p}（{os.path.getsize(p)} 字节）"))


def resolve_java() -> str:
    home = env("JAVA_HOME")
    if home:
        cand = os.path.join(home, "bin", "java")
        if os.path.isfile(cand):
            return cand
    which = shutil.which("java")
    return which or ""


def check_jdk(findings: list[Finding]) -> None:
    java = resolve_java()
    if not java:
        findings.append(Finding(
            "FAIL", "JDK",
            "找不到 java（JAVA_HOME 未指向 JDK 或 PATH 上没有 java）",
            "设置 JAVA_HOME，或在 unit 里用 Environment=JAVA_HOME=... 显式指定",
        ))
        return
    try:
        proc = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=30)
        raw = (proc.stderr or "") + (proc.stdout or "")
    except (OSError, subprocess.SubprocessError) as e:
        findings.append(Finding("FAIL", "JDK", f"执行 {java} -version 失败：{e}", "检查 JDK 安装与权限"))
        return
    major = parse_jdk_major(raw)
    if major is None:
        findings.append(Finding("FAIL", "JDK", f"无法解析版本：{raw.strip()[:80]}", "确认是标准 JDK（非 JRE 精简包）"))
        return
    if major < MIN_JDK_MAJOR:
        findings.append(Finding(
            "FAIL", "JDK",
            f"{java} 主版本 {major} < {MIN_JDK_MAJOR}",
            f"Boot 4 要求 JDK {MIN_JDK_MAJOR}+（本项目实跑 JDK 25）",
        ))
        return
    findings.append(Finding("PASS", "JDK", f"{java}（主版本 {major}）"))


def parse_jdk_major(raw: str) -> int | None:
    m = re.search(r'version\s+"([^"]+)"', raw) or re.search(r"\b(\d+(?:\.\d+)+)\b", raw)
    if not m:
        return None
    first = m.group(1).split(".")[0]
    # 1.8.0_x 这种老格式主版本是 8
    if first == "1" and len(m.group(1).split(".")) > 1:
        return int(m.group(1).split(".")[1])
    try:
        return int(first)
    except ValueError:
        return None


def parse_jdbc_url(url: str) -> tuple[str, int, str]:
    """jdbc:mysql://host:port/db?params → (host, port, db)。"""
    body = url[len("jdbc:"):] if url.startswith("jdbc:") else url
    parsed = urlparse(body)
    host = parsed.hostname or ""
    port = parsed.port or 3306
    db = unquote((parsed.path or "").lstrip("/"))
    if not host:
        raise ValueError(f"无法解析主机：{url!r}")
    return host, port, db


def check_db(findings: list[Finding], url: str, user: str, password: str, timeout: float) -> None:
    if not url:
        findings.append(Finding("WARN", "数据源连通", "未配置 PIVOTOS_MYSQL_URL，跳过连通检查"))
        return
    try:
        host, port, db = parse_jdbc_url(url)
    except ValueError as e:
        findings.append(Finding(
            "FAIL" if password or user else "WARN", "数据源连通",
            f"连接串不可解析：{e}",
            r'期望形态：jdbc:mysql://host:3306/db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowMultiQueries=false',
        ))
        return
    try:
        import pymysql
    except ImportError:
        findings.append(Finding(
            "WARN", "数据源连通",
            f"未安装 pymysql，跳过 {host}:{port}/{db or '(默认库)'} 的连通检查",
            "python3 -m pip install pymysql",
        ))
        return
    conn = None
    try:
        conn = pymysql.connect(
            host=host, port=port, user=user or None, password=password or None,
            database=db or None, connect_timeout=timeout, read_timeout=timeout,
            charset="utf8mb4",
        )
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
            cur.fetchone()
        target = f"{host}:{port}/{db}" if db else f"{host}:{port}"
        findings.append(Finding("PASS", "数据源连通", f"{target} 可连通（SELECT 1 通过）"))
    except Exception as e:  # noqa: BLE001 - 预检要汇总所有问题，不能一碰就崩
        cls = e.__class__.__name__
        findings.append(Finding(
            "WARN", "数据源连通",
            f"{host}:{port}/{db or '(默认库)'} 不可达（{cls}）",
            "先确认网络/账号/库名；应用仍会启动，就绪态请看 /health/ready 的 details。"
            "要改为阻断启动请加 --strict。",
        ))
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:  # noqa: BLE001
                pass


def check_ocr_env(findings: list[Finding]) -> None:
    prefix = env("TESSDATA_PREFIX")
    if not prefix:
        findings.append(Finding(
            "WARN", "OCR 环境",
            "TESSDATA_PREFIX 未设置",
            "不配则 Tika/OCR 上传与识别会报 1500（出处见《项目记忆 §3》）。"
            "本机为 /opt/homebrew/share/tessdata。",
        ))
        return
    if not os.path.isdir(prefix):
        findings.append(Finding(
            "WARN", "OCR 环境",
            f"TESSDATA_PREFIX 指向的目录不存在：{prefix}",
            "目录里需要 tessdata 语言包（chi_sim / eng）",
        ))
        return
    findings.append(Finding("PASS", "OCR 环境", f"TESSDATA_PREFIX={prefix}"))


def check_heap(findings: list[Finding]) -> None:
    if not env("JAVA_XMX") and not env("JAVA_XMS"):
        findings.append(Finding(
            "WARN", "堆上限",
            "JAVA_XMX / JAVA_XMS 均未设置 → 不限制堆（等同容器/系统默认）",
            "生产建议显式设置（如 JAVA_XMX=2g），否则容器 OOMKill 时机不可控；"
            "配合 unit 的 MemoryMax 一起用。",
        ))
        return
    findings.append(Finding(
        "PASS", "堆上限",
        f"JAVA_XMS={env('JAVA_XMS') or '(未设置)'} JAVA_XMX={env('JAVA_XMX') or '(未设置)'}",
    ))


def check_log_dir(findings: list[Finding], log_dir: str) -> None:
    d = env("PIVOTOS_LOG_DIR") or log_dir
    if not d:
        findings.append(Finding("WARN", "日志目录", "未指定日志目录"))
        return
    p = os.path.abspath(d)
    if not os.path.isdir(p):
        findings.append(Finding("WARN", "日志目录", f"目录不存在（启动时会自动创建）：{p}"))
        return
    if not os.access(p, os.W_OK):
        findings.append(Finding(
            "FAIL", "日志目录",
            f"目录不可写：{p}",
            f"检查运行账号权限；或在 unit 的 ReadWritePaths 里补上 {p}（ProtectSystem=strict 下必须显式放行）",
        ))
        return
    findings.append(Finding("PASS", "日志目录", f"{p} 可写"))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="PivotOS 启动前置检查：环境变量 / 端口 / 启动包 / JDK / 数据源连通")
    parser.add_argument("--profile", default=env("PIVOTOS_PROFILE") or "prod",
                        help="目标 profile（默认 prod；只有 prod 强制环境变量注入）")
    parser.add_argument("--port", type=int, default=int(env("SERVER_PORT") or 8080),
                        help="待检查的应用端口（默认 8080）")
    parser.add_argument("--jar", default=os.path.join(ROOT, "pivotos-admin-server", "target",
                                                      "pivotos-admin-server.jar"),
                        help="待检查的启动包路径")
    parser.add_argument("--log-dir", default=env("PIVOTOS_LOG_DIR") or "",
                        help="日志目录（默认随 LOG_DIR 推导）")
    parser.add_argument("--db-timeout", type=float, default=5.0,
                        help="数据源连通检查超时（秒，默认 5）")
    parser.add_argument("--skip-db", action="store_true", help="跳过数据源连通检查")
    parser.add_argument("--strict", action="store_true", help="把 WARN 也当作阻断（退出码 1）")
    args = parser.parse_args()

    if args.port < 1 or args.port > 65535:
        print(f"❌ 非法端口：{args.port}", file=sys.stderr)
        return 2

    findings: list[Finding] = []
    check_required_env(findings, args.profile)
    check_field_encrypt_key(findings, args.profile)
    check_port(findings, args.port)
    check_jar(findings, args.jar)
    check_jdk(findings)
    check_log_dir(findings, args.log_dir)
    if not args.skip_db:
        check_db(findings, env("PIVOTOS_MYSQL_URL"), env("PIVOTOS_MYSQL_USERNAME"),
                 env("PIVOTOS_MYSQL_PASSWORD"), args.db_timeout)
    check_ocr_env(findings)
    check_heap(findings)

    print("=" * 78)
    print(f"PivotOS 启动前置检查  profile={args.profile}  port={args.port}")
    print("=" * 78)
    for f in findings:
        mark = {"PASS": "✅", "WARN": "⚠️ ", "FAIL": "❌"}[f.level]
        print(f"{mark} [{f.level}] {f.name}：{f.detail}")
        if f.hint:
            print(f"      → {f.hint}")

    errors = [f for f in findings if f.level == "FAIL"]
    warns = [f for f in findings if f.level == "WARN"]
    print("-" * 78)
    print(f"合计：PASS {len(findings) - len(errors) - len(warns)} · "
          f"WARN {len(warns)} · FAIL {len(errors)}")

    if errors:
        print("❌ 存在阻断项，拒绝启动。")
        return 1
    if args.strict and warns:
        print("❌ --strict 模式下 WARN 也阻断，拒绝启动。")
        return 1
    print("✅ 前置检查通过，可以启动。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
