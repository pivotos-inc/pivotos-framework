#!/usr/bin/env python3
"""Flyway 迁移版本号红线校验（v3.1.0 P1-B4）。

## 为什么要有这个脚本

本仓的迁移版本号口径历史上是乱的：

* 库内最高已应用版本是 **V2.2.2**；
* 但 V3.1-D 新增的是 **V1.2.53**（低于 2.2.2），靠 ``out-of-order: true`` 硬塞进去；
* 而 admin-server 的 ``application.yml`` 开了 ``out-of-order``，**测试库没开** →
  于是每加一条 V1.2.x 迁移，8 个测试库就校验失败一次，必须整批重建（已踩三次）。

也就是说「新迁移用 V1.2.54」这个惯性本身就是问题来源。脚本把新口径固化成可执行红线：

> **新增迁移的版本号必须高于当前已跟踪迁移的最高版本（当前 V2.2.2，即 V2.2.3+）。
> 不再新增 V1.2.x。``out-of-order`` 仅作历史兼容保留，不再是新迁移的依靠。**

## 判定方式

只对**新增**（git 未跟踪 / 已暂存待提交）的迁移文件生效。历史里已存在的低版本号
（V1.2.0~V1.2.53）是既成事实，改它们会触发 Flyway checksum 失败（已应用的脚本字节冻结），
所以红线只管未来，不追溯。

用法：
    python3 scripts/ops/check_migration_version.py          # 校验 + 打印下一可用版本
    python3 scripts/ops/check_migration_version.py --all     # 连历史一起列（只报告，不追溯判红）
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

SQL_NAME_RE = re.compile(r"^V(\d+(?:\.\d+)*)__([A-Za-z0-9_]+)\.sql$")

# 测试专用 fixture 不进产物，也不参与版本红线
TEST_FIXTURES = {"V0.9.9", "V2.1.0"}


def parse_version(name: str):
    m = SQL_NAME_RE.match(name)
    if not m:
        return None, None
    ver = m.group(1)
    return tuple(int(p) for p in ver.split(".")), ver


def git(*args: str) -> list[str]:
    proc = subprocess.run(["git", "-C", str(REPO_ROOT), *args],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        return []
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def tracked_migrations() -> list[Path]:
    files = git("ls-files", "*/db/migration/V*.sql", "*/db/migration/**/V*.sql")
    out = []
    for f in files:
        p = REPO_ROOT / f
        if p.exists() and "/src/test/" not in p.as_posix():
            out.append(p)
    return out


def new_migrations() -> list[Path]:
    """未跟踪 + 已暂存的迁移文件（即「本批新增」）。"""
    candidates = set()
    candidates.update(git("ls-files", "--others", "--exclude-standard"))
    candidates.update(git("diff", "--cached", "--name-only", "--diff-filter=A"))
    out = []
    for f in candidates:
        if not f.endswith(".sql"):
            continue
        if "/db/migration/" not in f:
            continue
        p = REPO_ROOT / f
        if p.exists() and "/src/test/" not in p.as_posix():
            out.append(p)
    return out


def main() -> int:
    show_all = "--all" in sys.argv

    tracked = tracked_migrations()
    if not tracked:
        print("[migration-redline] FAIL 扫不到任何已跟踪迁移——路径口径变了，零命中不能当通过",
              file=sys.stderr)
        return 1

    versions: dict[str, tuple] = {}
    bad_name = []
    for p in tracked:
        vt, vs = parse_version(p.name)
        if vt is None:
            bad_name.append(f"{p}: 文件名不符合 V<版本>__<描述>.sql")
            continue
        if vs in TEST_FIXTURES:
            continue
        if vs in versions:
            bad_name.append(f"{p}: 版本号 {vs} 与 {versions[vs][1]} 重复")
            continue
        versions[vs] = (vt, p)

    if bad_name:
        print("[migration-redline] FAIL 命名问题：", file=sys.stderr)
        for b in bad_name:
            print(f"  - {b}", file=sys.stderr)
        return 1

    max_vt = max(vt for vt, _ in versions.values())
    max_vs = ".".join(str(x) for x in max_vt)

    print("=" * 72)
    print("[migration-redline] Flyway 迁移版本号红线")
    print("=" * 72)
    print(f"已跟踪迁移（不含测试 fixture）：{len(versions)} 条")
    print(f"当前最高版本：V{max_vs}")
    next_vs = ".".join(str(x) for x in (max_vt[:-1] + (max_vt[-1] + 1,)))
    print(f"下一可用版本：V{next_vs}　（红线：新迁移必须 > V{max_vs}，不得再用 V1.2.x）")

    if show_all:
        for vs in sorted(versions, key=lambda s: [int(x) for x in s.split(".")]):
            print(f"  V{vs}  {versions[vs][1].as_posix().replace(str(REPO_ROOT) + '/', '')}")

    new_files = new_migrations()
    if not new_files:
        print("[migration-redline] PASS 本批没有新增迁移，无版本号红线风险")
        return 0

    print("-" * 72)
    print(f"本批新增迁移 {len(new_files)} 条：")
    violations = []
    for p in new_files:
        vt, vs = parse_version(p.name)
        if vt is None:
            violations.append(f"{p.name}: 文件名不符合 V<版本>__<描述>.sql")
            continue
        print(f"  V{vs}  {p.as_posix().replace(str(REPO_ROOT) + '/', '')}")
        if vs in versions:
            violations.append(f"V{vs}: 版本号已被占用（{versions[vs][1].name}）")
        elif vt <= max_vt:
            violations.append(
                f"V{vs}: 低于当前最高版本 V{max_vs} —— 新迁移必须更高。"
                f"低版本号迁移要靠 out-of-order 才能进库，而测试库没开，"
                f"每加一条就得重建 8 个测试库（已踩三次）。请改用 V{next_vs}+。")

    if violations:
        print("[migration-redline] FAIL 版本号红线违反：", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1

    print(f"[migration-redline] PASS 新增迁移版本号均高于 V{max_vs}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
