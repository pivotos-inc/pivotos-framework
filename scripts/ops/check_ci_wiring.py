#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CI 环境变量与测试配置的接线门禁（V3.1-H · B5）。

要守的缺陷（2026-10-11 取证，非设想）
------------------------------------
`.github/workflows/ci.yml` 的 test job 注入了一组环境变量，而各模块的测试 yml
**一个都没消费**——全仓检索 `MYSQL_HOST` 只在 ci.yml 里出现过一次。
与此同时三个测试 yml 与四个租户 IT 类把 dev 主机 IP 硬编码在连接串里。

结果是 CI 的 test job 从来没有真正连上过它自己起的 MySQL / Redis 容器：
它一直在试图连接 dev 的那台公网机器。这条路径有两个方向都很糟——
要么 Runner 连不上（红灯，但看不出来根因是「配置没接上」），
要么哪天连得上了，CI 就会把测试数据写进别人在用的开发实例，而流水线依然显示通过。

这类缺陷的共同点是**零信号**：注入没被消费，既不会报错也不会警告。
所以只能靠机器比对，跟 `check_env_var_docs.py`（文档 vs 代码）是同一套思路。

三组检查
--------
  C 组  ci.yml 注入的每个环境变量，必须在别的文件里被真正引用
  H 组  src/test 下不得再出现硬编码的中间件地址（允许的白名单除外）
  D 组  默认值漂移：Python/Java/yml 里几处「同义默认值」必须逐字相同

退出码：0 = 全部通过；1 = 存在未接线项。

用法：
    python3 scripts/ops/check_ci_wiring.py
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
CI_YML = ROOT / ".github" / "workflows" / "ci.yml"

# 不被检查的变量：GitHub Actions 内建 / 插件自带的键，本来就不是给业务配置用的。
GITHUB_BUILTIN = {"GITHUB_TOKEN", "HOME", "CI", "GITHUB_ENV", "GITHUB_PATH"}

# 允许出现硬编码地址的**具体文件 + 原因**。
# 新增一个就必须在下面写明理由；理由写不出来就说明它不该硬编码。
ADDRESS_LITERAL = "175.24.176.176"
HARDCODE_ALLOW_LIST = {
    "pivotos-starters/pivotos-starter-tenant/src/test/java/com/pivotos/starter/tenant/it/TestDbDefaults.java":
        "dev 中间件默认值的唯一出处（Bootstrap 默认值），其余位置一律通过本类或占位符引用",
}

# 允许出现 IP 字面量的路径后缀：*IT 真机测试的文档注释里写明 dev 地址是示例，不参与默认回归。
ALLOWED_SUFFIXES = ("IT.java",)


def read_ci_env_keys() -> list[str]:
    """取 ci.yml 里所有 env 块的变量名（键名）。"""
    keys: list[str] = []
    for raw in CI_YML.read_text(encoding="utf-8").splitlines():
        stripped = raw.strip()
        # env 块里的条目形态是 "- KEY: value" 或 "KEY: value"
        matched = re.match(r"^-?\s*([A-Z][A-Z0-9_]*)\s*:", stripped)
        if matched and (stripped.startswith("- ") or re.match(r"^\s*env:", stripped)):
            continue
        if matched:
            keys.append(matched.group(1))
    return keys


def repo_files() -> list[Path]:
    skip_dirs = {".git", "target", "node_modules", "dist", ".idea", "data", "spike"}
    out: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in skip_dirs]
        for name in filenames:
            out.append(Path(dirpath) / name)
    return out


def check_ci_vars_are_consumed() -> list[str]:
    keys = [k for k in read_ci_env_keys() if k not in GITHUB_BUILTIN]
    if not keys:
        return ["ci.yml 里没解析到任何 env 变量（解析器失效？门禁自己先报警）"]

    contents: list[tuple[Path, str]] = []
    for path in repo_files():
        if path == CI_YML:
            continue
        if path.suffix.lower() not in {".yml", ".yaml", ".java", ".py", ".properties", ".ts"}:
            continue
        try:
            contents.append((path, path.read_text(encoding="utf-8", errors="ignore")))
        except OSError:
            continue

    unused: list[str] = []
    for key in keys:
        consumed = any(key in text for _, text in contents)
        if not consumed:
            unused.append(key)
    return [f"ci.yml 注入了 {key}，但全仓（除 ci.yml 自身）零引用 —— 注入不生效且无任何报错"
            for key in unused]


PLACEHOLDER = re.compile(r"\$\{[^{}]*\}")


def strip_placeholders(line: str) -> str:
    """去掉所有 `${...}` 占位符表达式（含嵌套：由内向外反复剥离）。

    关键：像 `host: ${PIVOTOS_TEST_REDIS_HOST:${REDIS_HOST:175.24.176.176}}` 这种形态里，
    字面量出现在**默认值位置**，是允许且必要的——它保证本机不改任何环境变量也能跑。
    真正要抓的是「剥掉占位符之后还剩下的裸地址」。
    """
    current = line
    while True:
        stripped = PLACEHOLDER.sub("", current)
        if stripped == current:
            return current
        current = stripped


def check_no_hardcoded_address_in_tests() -> list[str]:
    """只查两类真正会造成「连错机器」的写法：

      1. 测试资源配置文件里，剥掉占位符后仍是裸地址；
      2. Java 测试源码里出现 `jdbc:mysql://<裸地址>`（这种字符串一定会被拿去连库）。

    刻意不管另外两类出现位置：JSON 样例里的 `ip` 字段是测试数据而非连接目标；
    真机 *IT 文档注释里的 dev 地址只是示例，默认回归不收录这些类。
    """
    hits: list[str] = []
    for path in repo_files():
        rel = path.relative_to(ROOT).as_posix()
        if "/src/test/" not in rel:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue

        if "/src/test/resources/" in rel and path.suffix.lower() in {".yml", ".yaml", ".properties"}:
            for lineno, line in enumerate(text.splitlines(), start=1):
                if ADDRESS_LITERAL in strip_placeholders(line):
                    hits.append(
                        f"{rel}:{lineno} 配置文件里出现裸地址（应改走 env 占位符）："
                        f"{line.strip()[:130]}")
            continue

        if not rel.endswith(".java") or rel.endswith(ALLOWED_SUFFIXES) or rel in HARDCODE_ALLOW_LIST:
            continue
        needle = f"jdbc:mysql://{ADDRESS_LITERAL}"
        if needle in text:
            lineno = next((i for i, line in enumerate(text.splitlines(), start=1)
                           if needle in line), 0)
            hint = "改走 TestDbDefaults 或 ${PIVOTOS_TEST_MYSQL_HOST:...} 占位符"
            hits.append(
                f"{rel}:{lineno} Java 测试里硬编码了 JDBC 连接串（{hint}）："
                f"{text.splitlines()[max(lineno - 1, 0)].strip()[:120]}")
    return hits


def check_default_values_do_not_drift() -> list[str]:
    """Java 侧的默认值常量，必须与 yml 占位符里的默认值逐字一致。"""
    java = ROOT / ("pivotos-starters/pivotos-starter-tenant/src/test/java/"
                   "com/pivotos/starter/tenant/it/TestDbDefaults.java")
    if not java.exists():
        return [f"缺少默认值出处文件：{java.name}（删了就等于把默认值散回各处）"]

    text = java.read_text(encoding="utf-8")
    host_match = re.search(r'MYSQL_HOST\s*=\s*"([^"]+)"', text)
    port_match = re.search(r'MYSQL_PORT\s*=\s*"([^"]+)"', text)
    if not host_match or not port_match:
        return ["TestDbDefaults 里读不到 MYSQL_HOST / MYSQL_PORT 默认值常量"]

    problems: list[str] = []
    expected_host, expected_port = host_match.group(1), port_match.group(1)

    # yml 里的占位符默认值必须与之一致
    for path in repo_files():
        rel = path.relative_to(ROOT).as_posix()
        if "/src/test/resources/" not in rel or not path.name.endswith((".yml", ".yaml")):
            continue
        body = path.read_text(encoding="utf-8", errors="ignore")
        for var, expected in (("MYSQL_HOST", expected_host), ("MYSQL_PORT", expected_port)):
            for found in re.findall(r"\$\{[A-Z0-9_]+:\$\{?" + var + r":([^}]+)\}?", body):
                if found != expected:
                    problems.append(
                        f"{rel}：占位符默认值 {found} 与 TestDbDefaults.{var}（{expected}）不一致 —— "
                        f"两边各写一个默认值，迟早只在其中一处被改掉")

    # Java 注释里的占位符示例同理（注释会被人当成用法抄走）
    if expected_host not in text:
        problems.append("TestDbDefaults 的文档注释里看不到默认值常量，示例与实际会脱节")
    return problems


def main() -> int:
    print("=" * 78)
    print("CI 环境变量接线检查")
    print("=" * 78)

    groups = [
        ("C 组 ci.yml 注入的变量必须被真正消费", check_ci_vars_are_consumed),
        ("H 组 测试配置里不得硬编码中间件地址", check_no_hardcoded_address_in_tests),
        ("D 组 同义默认值不得漂移", check_default_values_do_not_drift),
    ]

    failed = False
    for title, probe in groups:
        problems = probe()
        if problems:
            failed = True
            print(f"\n❌ {title}：")
            for item in problems:
                print(f"   - {item}")
        else:
            print(f"\n✅ {title}")

    print("-" * 78)
    if failed:
        print("❌ 存在未接线项：CI 注入环境变量而配置不消费，是本项目已经发生过一次的事故形态。")
        return 1
    print("✅ CI 注入的变量全部有消费方，测试配置无硬编码地址。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
