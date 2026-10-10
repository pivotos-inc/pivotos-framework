#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S133 - V3 spike：数据层跨模块耦合扫描（哪些 SQL / 语句会同时触碰两个业务 Plugin 的表）

为什么扫这个
------------
「通信抽象」只能治理 **Java 方法调用** 层面的跨模块耦合；真正的微服务拆分杀手是数据层：
一条 SQL JOIN 了两张分属不同业务的表，拆库后必炸，且没有任何编译期信号。
本扫描把这类 SQL 一次性抓出来，作为成本测算里「数据层拆库工作量」的依据。

做法
----
1. 先建立 `表名 -> 所属模块` 索引：扫描每个 Maven 模块 src/main/java 下的 `@TableName("xxx")`
   （含 BaseDO 继承体系），必要时补充 XML `<mapper namespace>` 的兜底。
2. 再扫所有 **/mapper/**/*.xml + Java 上的 `@Select/@Update` 注解 SQL，切分成单条语句，
   提取语句里出现的表名（`from` / `join` / `update` / `into` / `delete from` 之后的表名，兼容反引号）。
3. 若同一条语句命中 >=2 个不同模块的表 → 记为「跨模块数据耦合」。

复跑
----
cd pivotos-framework && python3 spike/s-v3-cloud/tools/scan_cross_schema_sql.py [--out FILE]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

EXCLUDE_DIR_PARTS = {"target", "node_modules", ".git", "data"}
MODULE_ROOTS = ("pivotos-commons", "pivotos-starters", "pivotos-plugins", "pivotos-admin-server")

TABLENAME_RE = re.compile(r'@TableName\s*\(\s*(?:value\s*=\s*)?"([A-Za-z0-9_]+)"')
XMLNS_RE = re.compile(r"<mapper[^>]*namespace\s*=\s*\"([^\"]+)\"")
# from xxx / join xxx / update xxx / into xxx / delete from xxx
TABLE_TOKEN_RE = re.compile(
    r"\b(?:from|join|update|into)\s+`?([A-Za-z_][A-Za-z0-9_]{2,})`?", re.IGNORECASE
)
SELECT_SQL_RE = re.compile(r'@(?:Select|Update|Delete|Insert)\s*\(\s*(?:\{\s*)?"([^"]{16,})"', re.DOTALL)
ANNOT_RE = re.compile(r"@(Select|Update|Delete|Insert)\s*\(", re.IGNORECASE)
STR_RE = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')


def extract_annotation_sqls(content: str) -> list[str]:
    """
    MP 的 SQL 可能写在两种形态里：
      @Select("select ...")                       单段
      @Select({"select ...", "from ...", "..."})  多段字符串数组
    这里把同一个注解内的所有字符串常量拼接成一条完整 SQL，避免跨段 JOIN 被漏掉。
    """
    sqls: list[str] = []
    pos = 0
    while True:
        m = ANNOT_RE.search(content, pos)
        if not m:
            break
        i = m.end() - 1          # 指向 '('
        depth, j = 0, i
        while j < len(content):
            if content[j] == "(":
                depth += 1
            elif content[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        joined = " ".join(STR_RE.findall(content[i:j + 1]))
        if joined.strip():
            sqls.append(joined)
        pos = j + 1
    return sqls


def module_dirs(root: str) -> dict[str, str]:
    found: dict[str, str] = {}

    def aid(pom: str) -> str | None:
        try:
            tree = ET.parse(pom)
        except ET.ParseError:
            return None
        for child in list(tree.getroot()):
            tag = child.tag.split("}")[-1] if isinstance(child.tag, str) else ""
            if tag == "artifactId":
                return (child.text or "").strip()
        return None

    for base in MODULE_ROOTS:
        scan = os.path.join(root, base)
        if not os.path.isdir(scan):
            continue
        for dirpath, dirnames, filenames in os.walk(scan):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIR_PARTS]
            if "pom.xml" in filenames:
                a = aid(os.path.join(dirpath, "pom.xml"))
                if a:
                    found[os.path.abspath(dirpath)] = a
    admin = os.path.join(root, "pivotos-admin-server")
    a = aid(os.path.join(admin, "pom.xml"))
    if a:
        found[os.path.abspath(admin)] = a
    return found


def build_table_index(modules: dict[str, str]) -> tuple[dict[str, str], dict[str, str]]:
    """返回 ({table: module}, {module: 表数量})"""
    index: dict[str, str] = {}
    owner_extra: dict[str, set] = defaultdict(set)
    for module_dir, artifact in modules.items():
        src = os.path.join(module_dir, "src", "main", "java")
        if not os.path.isdir(src):
            continue
        for dirpath, dirnames, filenames in os.walk(src):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIR_PARTS]
            for fn in filenames:
                if not fn.endswith(".java"):
                    continue
                with open(os.path.join(dirpath, fn), "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                for tbl in TABLENAME_RE.findall(content):
                    # 同表被两个模块声明时，保留第一个，并记录
                    if tbl in index and index[tbl] != artifact:
                        owner_extra[tbl].add(artifact)
                        continue
                    index[tbl] = artifact
    counts = defaultdict(int)
    for tbl, mod in index.items():
        counts[mod] += 1
    return index, dict(counts)


def iter_sql_sources(modules: dict[str, str]):
    """产出 (module, file, sql_text, label)"""
    for module_dir, artifact in modules.items():
        base = os.path.join(module_dir, "src", "main")
        for kind, sub in (("resources", os.path.join(base, "resources")), ("java", os.path.join(base, "java"))):
            if not os.path.isdir(sub):
                continue
            for dirpath, dirnames, filenames in os.walk(sub):
                dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIR_PARTS]
                for fn in filenames:
                    path = os.path.join(dirpath, fn)
                    if kind == "resources":
                        if not (fn.endswith(".xml") and "mapper" in dirpath):
                            continue
                        try:
                            tree = ET.parse(path)
                        except ET.ParseError:
                            continue
                        for stmt in tree.getroot().iter():
                            tag = stmt.tag.split("}")[-1] if isinstance(stmt.tag, str) else ""
                            if tag not in ("select", "insert", "update", "delete"):
                                continue
                            sid = stmt.attrib.get("id", "?")
                            sql = "".join(stmt.itertext())
                            yield artifact, path, sql, f"{tag}#{sid}"
                    else:
                        if not fn.endswith(".java"):
                            continue
                        with open(path, "r", encoding="utf-8", errors="ignore") as f:
                            content = f.read()
                        for i, sql in enumerate(extract_annotation_sqls(content), 1):
                            yield artifact, path, sql, f"annotation#{i}"


def main() -> int:
    ap = argparse.ArgumentParser()
    # 默认 = 本脚本所在 scripts/arch/ 的上两级 = pivotos-framework 根（V3-S1 从 spike 转正时修正过：
    # 原值取的是上三级，会指向工作区根，导致找不到 pivotos-admin-server/pom.xml）
    ap.add_argument("--root", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
    ap.add_argument("--out", default=None)
    ap.add_argument("--format", choices=["text", "json"], default="text")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    modules = module_dirs(root)
    table_index, table_counts = build_table_index(modules)

    findings = []
    total_sql = 0
    for module, path, sql, label in iter_sql_sources(modules):
        total_sql += 1
        # 去掉注释，避免注释里的表名被误伤
        sql_clean = re.sub(r"/\*.*?\*/", " ", sql, flags=re.DOTALL)
        sql_clean = re.sub(r"--[^\n]*", " ", sql_clean)
        tables = {t.lower() for t in TABLE_TOKEN_RE.findall(sql_clean)}
        owners = {}
        for t in tables:
            owner = table_index.get(t)
            if owner:
                owners[t] = owner
        if len(set(owners.values())) >= 2:
            findings.append({
                "module": module,
                "file": os.path.relpath(path, root),
                "label": label,
                "tables": sorted(f"{t}({o})" for t, o in owners.items()),
                "modules": sorted(set(owners.values())),
            })

    if args.format == "json":
        payload = {"tables_indexed": len(table_index), "per_module": table_counts,
                   "sql_scanned": total_sql, "cross_schema_sql": findings}
        text = json.dumps(payload, ensure_ascii=False, indent=2)
    else:
        lines = []
        lines.append("=" * 100)
        lines.append("S133 · 数据层跨模块耦合扫描（同一条 SQL 触碰 >=2 个业务 Plugin 的表）")
        lines.append("=" * 100)
        lines.append(f"扫描根      : {root}")
        lines.append(f"@TableName 索引表数 : {len(table_index)}")
        for mod, cnt in sorted(table_counts.items(), key=lambda kv: -kv[1]):
            lines.append(f"    {mod}: {cnt} 张表")
        lines.append(f"扫描 SQL 语句总数   : {total_sql}")
        lines.append(f"跨模块 SQL 命中     : {len(findings)}")
        lines.append("")
        lines.append("-" * 100)
        if not findings:
            lines.append("  （无）没有任何一条 SQL 同时触碰两个业务 Plugin 的表 —— 数据层天然可拆库")
        for f in findings:
            lines.append(f"  [{f['module']}] {f['file']} :: {f['label']}")
            lines.append(f"        tables: {', '.join(f['tables'])}")
        lines.append("")
        lines.append("=" * 100)
        lines.append("判定口径：命中项 = 拆库后必须改写成『应用层聚合 / 数据冗余 / 跨库视图』的工作量单位；")
        lines.append("          未索引到的表（如第三方组件自建表 warm-flow_*）不计入。")
        lines.append("=" * 100)
        text = "\n".join(lines)

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print(f"[written] {args.out}")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
