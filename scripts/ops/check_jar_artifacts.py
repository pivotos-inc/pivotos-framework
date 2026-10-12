#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fat jar 构件指纹核对（v3.1.0 P1-B5 · CI 产物取证）。

为什么单独做一个脚本
--------------------
本仓有两条**靠手工命令传承**的硬口径，一直是「谁记得谁做」：

  1. 内层 pivotos 构件的版本号必须全部等于期望版本
     —— **这是唯一可信的版本证据**。fat jar 的字节数不能当指纹：
        zip 条目里存了 mtime，同一份代码两次打包字节数本来就不一样。
  2. 默认形态的 fat jar 里不得残留 spring-cloud / nacos
     —— 多形态交付靠这条证明「没污染」。

它们只在收口报告里被手工跑一次，几乎没有防回归能力。现在搬进脚本，
既给 CI 用（产物归档的价值就在于事后能取证），也给本地收口用。

判据的形态（刻意选硬的）
------------------------
构件版本**不从文件名猜**：文件名可以被改名、甚至可以同名的不同版本混进来。
本脚本直接读内层 jar 的 ``META-INF/maven/<groupId>/<artifactId>/pom.properties``
 ——那是 Maven 在打包时写进去的元数据，改文件名动不了它。
读不到（有些 shade/fat 模块没有）才回落到文件名，并在输出里标注来源。

用法
----
    python3 scripts/ops/check_jar_artifacts.py
    python3 scripts/ops/check_jar_artifacts.py --jar path/to.jar
    python3 scripts/ops/check_jar_artifacts.py --expect 3.0.0
    python3 scripts/ops/check_jar_artifacts.py --forbid spring-cloud --forbid nacos
    python3 scripts/ops/check_jar_artifacts.py --json ci-jar-fingerprint.json

退出码：0 = 全部通过；1 = 存在不一致；2 = 输入不可用（jar 不存在等）。
"""

from __future__ import annotations

import argparse
import io
import json
import re
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JAR = REPO_ROOT / "pivotos-admin-server" / "target" / "pivotos-admin-server.jar"
GROUP_ID = "com.pivotos"
LIB_PREFIX = "BOOT-INF/lib/"

# 输出里的彩色（非 TTY 自动退化为空串，避免污染 CI 日志）
_COLOR = {
    "red": "",
    "green": "",
    "yellow": "",
    "dim": "",
}
if sys.stdout.isatty():
    _COLOR = {"red": "\033[31m", "green": "\033[32m", "yellow": "\033[33m", "dim": "\033[2m"}


def read_root_version(root_pom: Path) -> str | None:
    """读根 pom 的 project 版本。只取顶层 <version>，不解析整个 POM。"""
    text = root_pom.read_text(encoding="utf-8")
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("<parent>") or line.startswith("</parent>"):
            continue
        if re.fullmatch(r"<version>[^<${]+</version>", line):
            return line[len("<version>"): -len("</version>")]
    return None


def artifact_version_from_metadata(inner_bytes: bytes, artifact: str) -> str | None:
    """从内层 jar 的 Maven 元数据里读版本号（硬证据）。"""
    try:
        with zipfile.ZipFile(io.BytesIO(inner_bytes)) as zf:
            for name in zf.namelist():
                if not name.startswith("META-INF/maven/"):
                    continue
                if not name.endswith("/pom.properties"):
                    continue
                if f"/{GROUP_ID}/" not in name and not name.endswith(f"/{artifact}/pom.properties"):
                    continue
                props = zf.read(name).decode("utf-8", errors="replace")
                for line in props.splitlines():
                    if line.startswith("version="):
                        return line[len("version="):].strip()
    except (zipfile.BadZipFile, OSError, ValueError):
        return None
    return None


def version_from_filename(file_name: str) -> str | None:
    """回落方案：pivotos-xxx-3.0.0.jar → 3.0.0（去掉 -SNAPSHOT / 分类器后缀）。"""
    stem = file_name[:-4] if file_name.endswith(".jar") else file_name
    parts = stem.split("-")
    if len(parts) < 2:
        return None
    tail = parts[-1]
    if not re.fullmatch(r"\d[\w.\-]*", tail):
        return None
    return tail


def main() -> int:
    parser = argparse.ArgumentParser(description="fat jar 内层构件版本 / 残留核对")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR, help=f"待核对的 jar（默认 {DEFAULT_JAR.name}）")
    parser.add_argument("--expect", default=None, help="期望版本（默认取根 pom 的 project 版本）")
    parser.add_argument("--forbid", action="append", default=[], help="残留红线：jar 条目名里不得出现的子串，可重复")
    parser.add_argument("--json", type=Path, default=None, help="冻结产物（便于 CI 归档后回溯）")
    args = parser.parse_args()

    jar: Path = args.jar
    if not jar.is_file():
        print(f"FAIL jar 不存在：{jar}")
        print("提示：先 mvn clean package -DskipTests（注意这会清掉再重建 fat jar）")
        return 2

    expect = args.expect or read_root_version(REPO_ROOT / "pom.xml")
    if not expect:
        print("FAIL 无法确定期望版本：根 pom 里没读到顶层 <version>，请用 --expect 显式给定")
        return 2

    print("=" * 78)
    print(f"fat jar 构件指纹核对：{jar}")
    print(f"  文件            : {jar.stat().st_size:,} 字节（仅作量级参考，不作指纹）")
    print(f"  期望版本        : {expect}")
    print(f"  构件坐标前缀    : {GROUP_ID}（+ 文件名兜底）")
    if args.forbid:
        print(f"  残留红线        : {args.forbid}")
    print("=" * 78)

    findings: list[dict] = []
    problems: list[str] = []

    with zipfile.ZipFile(jar) as outer:
        entries = [n for n in outer.namelist() if n.startswith(LIB_PREFIX) and n.endswith(".jar")]

        # 1) pivotos 自身构件：既按文件名前缀扫，也按内层 maven 元数据确认 groupId
        candidate = [n for n in entries if Path(n).name.startswith("pivotos-")]
        for name in sorted(candidate):
            file_name = Path(name).name
            try:
                inner = outer.read(name)
            except (KeyError, OSError) as exc:  # pragma: no cover - 结构异常才发生
                problems.append(f"{file_name}: 读取失败 {exc}")
                continue

            artifact = None
            meta_group = None
            try:
                with zipfile.ZipFile(io.BytesIO(inner)) as zf:
                    for mn in zf.namelist():
                        if mn.endswith("/pom.properties") and mn.startswith("META-INF/maven/"):
                            props = zf.read(mn).decode("utf-8", errors="replace")
                            for line in props.splitlines():
                                if line.startswith("artifactId="):
                                    artifact = line[len("artifactId="):].strip()
                                elif line.startswith("groupId="):
                                    meta_group = line[len("groupId="):].strip()
                            break
            except (zipfile.BadZipFile, OSError, ValueError):
                pass

            if meta_group is not None and meta_group != GROUP_ID:
                # 第三方恰好以 pivotos- 开头（本仓暂无，但别让它悄悄进来）
                findings.append({
                    "file": file_name, "version": None, "source": "skipped",
                    "note": f"groupId={meta_group} 非 {GROUP_ID}",
                })
                continue

            ver_meta = artifact_version_from_metadata(inner, artifact or "")
            ver = ver_meta or version_from_filename(file_name)
            source = "pom.properties" if ver_meta else "filename"

            findings.append({
                "file": file_name,
                "artifact": artifact,
                "version": ver,
                "source": source,
            })
            if ver != expect:
                problems.append(f"{file_name}: 版本 {ver}（来源 {source}）≠ 期望 {expect}")

        # 2) 残留红线
        for token in args.forbid:
            hit = sorted(n for n in entries if token in Path(n).name.lower())
            for n in hit:
                problems.append(f"残留红线命中 [{token}]：{Path(n).name}")

    pivotos_rows = [f for f in findings if f.get("source") != "skipped"]
    skipped_rows = [f for f in findings if f.get("source") == "skipped"]

    print(f"\npivotos 构件：{len(pivotos_rows)} 个")
    versions: dict[str, int] = {}
    for row in pivotos_rows:
        ver = row.get("version") or "<未知>"
        versions[ver] = versions.get(ver, 0) + 1
    for ver, count in sorted(versions.items()):
        mark = "OK " if ver == expect else "!! "
        print(f"  {mark}{ver:<12} × {count}")
    for row in pivotos_rows:
        if row.get("version") != expect:
            print(f"      {_COLOR['red']}× {row['file']}{_COLOR['dim']}")
    for row in skipped_rows:
        print(f"  -- 跳过 {row['file']}（{row.get('note')}）")

    if args.forbid:
        print(f"\n残留红线：")
        for token in args.forbid:
            hits = [p for p in problems if p.startswith(f"残留红线命中 [{token}]")]
            if hits:
                for h in hits:
                    print(f"  {_COLOR['red']}{h}{_COLOR['dim']}")
            else:
                print(f"  OK [{token}] 零命中")

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps({
            "jar": str(jar),
            "jar_bytes": jar.stat().st_size,
            "expect": expect,
            "pivotos_artifacts": pivotos_rows,
            "problems": problems,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n已写出：{args.json}")

    print()
    if problems:
        print(f"{_COLOR['red']}FAIL{_COLOR['dim']} 共 {len(problems)} 项不一致：")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"{_COLOR['green']}PASS{_COLOR['dim']} "
          f"{len(pivotos_rows)} 个 pivotos 构件全为 {expect}"
          + (f"，{len(args.forbid)} 条残留红线零命中" if args.forbid else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
