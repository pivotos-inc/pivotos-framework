#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S133 - V3「形态革命」spike：隐性跨模块调用扫描器

目的
----
回答路线图第五节「风险表」第一条：`-api` Facade 之外是否存在隐性跨模块调用。
这是 Go / No-Go 的**前置条件**：若存在大量隐性跨实现模块的耦合，
「通信抽象 + 全量 Cloud Starter 插件化」（微服务形态）的成本会失控。

口径
----
1. 只扫 src/main/java（生产代码），测试代码不算耦合欠款。
2. 「合规通道」= 调用目标位于 `*-api` 契约模块（Facade / DTO / 枚举），或位于公共基座
   （pivotos-common-core / pivotos-common-api / pivotos-starter-core）。
3. 「隐性欠款」= 调用目标位于**实现模块**（非 -api、非基座）。这类调用一旦拆成微服务，
   本地方法调用会变成跨进程调用，必须逐个改为 Facade + HTTP 通道，属于真实工作量。
4. 同模块内部引用不计；api 模块反向依赖实现模块单独列 Konw "反向依赖" 视为架构违规。

复跑
----
cd pivotos-framework && python3 spike/s-v3-cloud/tools/scan_cross_module.py \
    [--format text|json] [--out FILE]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

# 公共基座白名单：任何模块依赖这些模块都是基线，不算欠款
BASE_MODULES = {
    "pivotos-common-core",
    "pivotos-common-api",
    "pivotos-starter-core",
}

# 不参与构建的样例工程（见 项目记忆：16 个 migration 样例 pom 不参与构建）
EXCLUDE_DIR_PARTS = {
    "target",
    "data",
    "node_modules",
    ".git",
}

MODULE_ROOTS = ("pivotos-commons", "pivotos-starters", "pivotos-plugins", "pivotos-admin-server")

IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;")
ARTIFACT_RE = re.compile(r"<artifactId>([^<]+)</artifactId>")


def pom_artifact_id(pom_path: str) -> str | None:
    """取 project 自身的 artifactId（必须跳过 <parent> / <plugin> 内的同名标签，否则会解析成父聚合名）"""
    try:
        tree = ET.parse(pom_path)
    except ET.ParseError:
        return None
    root = tree.getroot()
    for child in list(root):
        # pom 带 maven namespace，tag 形如 {http://maven.apache.org/POM/4.0.0}artifactId
        tag = child.tag.split("}")[-1] if isinstance(child.tag, str) else ""
        if tag == "artifactId":
            return (child.text or "").strip()
    return None


def discover_modules(root: str) -> dict[str, str]:
    """返回 {module_dir_abs_path: artifactId}"""
    modules: dict[str, str] = {}
    for scan_root_name in MODULE_ROOTS:
        scan_root = os.path.join(root, scan_root_name)
        if not os.path.isdir(scan_root):
            continue
        for dirpath, dirnames, filenames in os.walk(scan_root):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIR_PARTS]
            if "pom.xml" not in filenames:
                continue
            artifact = pom_artifact_id(os.path.join(dirpath, "pom.xml"))
            if artifact:
                modules[os.path.abspath(dirpath)] = artifact
    # admin-server 自身
    admin = os.path.join(root, "pivotos-admin-server")
    artifact = pom_artifact_id(os.path.join(admin, "pom.xml"))
    if artifact:
        modules[os.path.abspath(admin)] = artifact
    return modules


def build_package_index(modules: dict[str, str]) -> tuple[dict[str, str], list[str]]:
    """
    构建 {package_fqn: artifactId}。
    取最长前缀匹配用；同一包被两个模块拥有时记为冲突（正常不应发生）。
    """
    pkg_index: dict[str, str] = {}
    conflicts: list[str] = []
    for module_dir, artifact in modules.items():
        src = os.path.join(module_dir, "src", "main", "java")
        if not os.path.isdir(src):
            continue
        for dirpath, _, filenames in os.walk(src):
            if not any(fn.endswith(".java") for fn in filenames):
                continue
            rel = os.path.relpath(dirpath, src)
            pkg = rel.replace(os.sep, ".")
            if pkg == ".":
                continue
            if pkg in pkg_index and pkg_index[pkg] != artifact:
                # 同名包出现在多个模块（如 com.pivotos.generator.service 同时存在于 generator 与
                # generator-api）：以 -api 契约为归属优先，因为外部应当只引用契约一侧；
                # 依赖声明层面的偏差另由 audit_pom_deps 单独报告。
                prefer_api = artifact.endswith("-api") and not pkg_index[pkg].endswith("-api")
                conflicts.append(f"{pkg}: {pkg_index[pkg]} <-> {artifact}")
                if not prefer_api:
                    continue
            pkg_index[pkg] = artifact
    return pkg_index, conflicts


def resolve_module(fqn: str, pkg_index: dict[str, str]) -> str | None:
    """最长前缀匹配：把类的 FQN 解析到所属模块"""
    parts = fqn.split(".")
    for i in range(len(parts) - 1, 0, -1):
        pkg = ".".join(parts[:i])
        if pkg in pkg_index:
            return pkg_index[pkg]
    return None


def audit_pom_deps(modules: dict[str, str]) -> list[dict]:
    """
    pom 依赖声明审计：找出「直接依赖了 Plugin 实现模块」而非其 -api 契约模块的情形。
    这类声明本身不一定产生隐性调用，但会把实现模块的 jar 强行拉进编译/运行 classpath，
    使「看似只依赖契约」的模块实际被绑定到实现，是拆分时最难发现的耦合。
    """
    findings: list[dict] = []
    for module_dir, artifact in modules.items():
        pom = os.path.join(module_dir, "pom.xml")
        if not os.path.isfile(pom):
            continue
        # admin-server 是聚合壳，天然依赖全部 Plugin 实现模块，不算耦合欠款
        if artifact == "pivotos-admin-server":
            continue
        try:
            tree = ET.parse(pom)
        except ET.ParseError:
            continue

        def local_name(el):
            return el.tag.split("}")[-1] if isinstance(el.tag, str) else ""

        for dep in tree.getroot().iter():
            if local_name(dep) != "dependency":
                continue
            values = {local_name(c): (c.text or "").strip() for c in dep}
            group, art = values.get("groupId", ""), values.get("artifactId", "")
            scope = values.get("scope", "compile")
            if group != "com.pivotos" or scope not in ("compile", "runtime", ""):
                continue
            if not art.startswith("pivotos-plugin-"):
                continue
            if art == artifact or art == artifact + "-api":
                continue
            if art.endswith("-api"):
                continue
            # 自己依赖自己所属 api 跳过
            kind = "PLUGIN_IMPL"
            findings.append({"from": artifact, "to": art, "scope": scope, "kind": kind,
                             "pom": os.path.relpath(pom, os.path.dirname(module_dir))})
    return findings


def collect_imports(root: str, modules: dict[str, str]):
    """返回 list[(src_module, target_fqn, java_file, line_no)]"""
    rows = []
    for module_dir, artifact in modules.items():
        src = os.path.join(module_dir, "src", "main", "java")
        if not os.path.isdir(src):
            continue
        for dirpath, dirnames, filenames in os.walk(src):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIR_PARTS]
            for fn in filenames:
                if not fn.endswith(".java"):
                    continue
                path = os.path.join(dirpath, fn)
                try:
                    with open(path, "r", encoding="utf-8", errors="ignore") as f:
                        for lineno, line in enumerate(f, 1):
                            m = IMPORT_RE.match(line)
                            if not m:
                                continue
                            rows.append((artifact, m.group(1), path, lineno))
                except OSError:
                    pass
    return rows


def classify(src_module: str, target_module: str) -> str:
    if src_module == target_module:
        return "SAME"
    if target_module in BASE_MODULES:
        return "BASE"          # 公共基座（common-core / common-api / starter-core），合规
    if target_module.endswith("-api"):
        return "CONTRACT"      # 契约模块，合规（Facade / DTO / 枚举）
    # starter→plugin 实现：基础设施反向依赖业务，属于必须先治理的架构违规
    if src_module.startswith("pivotos-starter-") and target_module.startswith("pivotos-plugin-"):
        return "REVERSE"
    # api 模块反向依赖实现模块：同样是架构违规
    if src_module.endswith("-api") and not target_module.endswith("-api"):
        return "REVERSE"
    # 业务 Plugin → 基础设施 Starter：横向基础设施不跨进程边界，不算微服务化欠款
    if target_module.startswith("pivotos-starter-"):
        return "INFRA"
    return "DEBT"              # Plugin ↔ Plugin 实现模块：真正的微服务化欠款


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..")))
    ap.add_argument("--format", choices=["text", "json"], default="text")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    modules = discover_modules(root)
    pkg_index, conflicts = build_package_index(modules)
    rows = collect_imports(root, modules)

    edges: dict[tuple[str, str, str], dict] = {}
    for src_module, target_fqn, path, lineno in rows:
        if not target_fqn.startswith("com.pivotos"):
            continue
        target_module = resolve_module(target_fqn, pkg_index)
        if target_module is None:
            continue
        kind = classify(src_module, target_module)
        if kind == "SAME":
            continue
        key = (src_module, target_module, kind)
        rec = edges.setdefault(key, {"targets": set(), "count": 0, "samples": []})
        rec["targets"].add(target_fqn)
        rec["count"] += 1
        if len(rec["samples"]) < 3:
            rec["samples"].append(f"{os.path.relpath(path, root)}:{lineno} -> {target_fqn}")

    debts = sorted(
        [k + (v,) for k, v in edges.items() if k[2] == "DEBT"],
        key=lambda item: (-item[3]["count"], item[0]),
    )
    reverses = sorted(
        [k + (v,) for k, v in edges.items() if k[2] == "REVERSE"],
        key=lambda item: (-item[3]["count"], item[0]),
    )
    contracts = sorted(
        [k + (v,) for k, v in edges.items() if k[2] == "CONTRACT"],
        key=lambda item: (-item[3]["count"], item[0]),
    )
    infras = sorted(
        [k + (v,) for k, v in edges.items() if k[2] == "INFRA"],
        key=lambda item: (-item[3]["count"], item[0]),
    )
    bases = sorted(
        [k + (v,) for k, v in edges.items() if k[2] == "BASE"],
        key=lambda item: (-item[3]["count"], item[0]),
    )

    debt_imports = sum(v["count"] for _a, _b, _k, v in debts)
    contract_imports = sum(v["count"] for _a, _b, _k, v in contracts)
    pom_findings = audit_pom_deps(modules)

    if args.format == "json":
        payload = {
            "module_count": len(modules),
            "package_conflicts": conflicts,
            "debt_edges": [
                {"from": a, "to": b, "imports": v["count"], "distinct_classes": len(v["targets"]),
                 "samples": v["samples"]}
                for (a, b, _k, v) in debts
            ],
            "reverse_edges": [
                {"from": a, "to": b, "imports": v["count"], "samples": v["samples"]}
                for (a, b, _k, v) in reverses
            ],
            "contract_edges": [
                {"from": a, "to": b, "imports": v["count"], "distinct_classes": len(v["targets"])}
                for (a, b, _k, v) in contracts
            ],
            "infra_edges": [
                {"from": a, "to": b, "imports": v["count"], "distinct_classes": len(v["targets"])}
                for (a, b, _k, v) in infras
            ],
            "pom_impl_dependencies": pom_findings,
            "totals": {
                "debt_edges": len(debts),
                "debt_imports": debt_imports,
                "reverse_edges": len(reverses),
                "contract_edges": len(contracts),
                "contract_imports": contract_imports,
                "infra_edges": len(infras),
                "base_edges": len(bases),
            },
        }
        text = json.dumps(payload, ensure_ascii=False, indent=2)
    else:
        lines = []
        lines.append("=" * 100)
        lines.append("S133 · 隐性跨模块调用扫描（-api Facade 之外的跨 Plugin / Starter 实现模块引用）")
        lines.append("=" * 100)
        lines.append(f"扫描根        : {root}")
        lines.append(f"Maven 模块数  : {len(modules)}")
        lines.append(f"包索引条目    : {len(pkg_index)}")
        lines.append(f"包冲突        : {len(conflicts)}" + ("" if not conflicts else f" -> {conflicts}"))
        lines.append("")
        lines.append("【总计】")
        lines.append(f"  欠款边 DEBT      : {len(debts)} 条 / import {debt_imports} 次")
        lines.append(f"  违规边 REVERSE   : {len(reverses)} 条")
        lines.append(f"  契约边 CONTRACT  : {len(contracts)} 条 / import {contract_imports} 次")
        lines.append(f"  基础设施边 INFRA : {len(infras)} 条（Plugin → Starter，非跨服务边界）")
        lines.append(f"  基座边 BASE      : {len(bases)} 条")
        lines.append(f"  pom 实现依赖     : {len(pom_findings)} 条（直接依赖 Plugin 实现模块而非 -api）")
        lines.append("")
        lines.append("-" * 100)
        lines.append(f"【欠款】隐性跨实现模块调用：{len(debts)} 条边")
        lines.append("-" * 100)
        if not debts:
            lines.append("  （无）零隐性调用 —— 所有跨模块调用都走 -api 契约通道")
        for src, tgt, _k, v in debts:
            lines.append(f"  {src}  ->  {tgt}   (import {v['count']} 次 / 涉及 {len(v['targets'])} 个类)")
            for s in v["samples"]:
                lines.append(f"        · {s}")
        lines.append("")
        lines.append("-" * 100)
        lines.append(f"【违规】api 模块反向依赖实现模块：{len(reverses)} 条边")
        lines.append("-" * 100)
        if not reverses:
            lines.append("  （无）")
        for src, tgt, _k, v in reverses:
            lines.append(f"  {src}  ->  {tgt}   (import {v['count']} 次)")
            for s in v["samples"]:
                lines.append(f"        · {s}")
        lines.append("")
        lines.append("-" * 100)
        lines.append(f"【合规】走 -api 契约通道的跨模块调用：{len(contracts)} 条边")
        lines.append("-" * 100)
        for src, tgt, _k, v in contracts:
            lines.append(f"  {src}  ->  {tgt}   (import {v['count']} 次 / 涉及 {len(v['targets'])} 个类)")
        lines.append("")
        lines.append("-" * 100)
        lines.append(f"【依赖声明】直接依赖 Plugin 实现模块（而非 -api 契约）：{len(pom_findings)} 条")
        lines.append("-" * 100)
        if not pom_findings:
            lines.append("  （无）")
        for f in pom_findings:
            lines.append(f"  {f['from']}  ->  {f['to']}   (scope={f['scope']}, {f['pom']})")
        lines.append("")
        lines.append("-" * 100)
        lines.append(f"【参考】Plugin → 基础设施 Starter：{len(infras)} 条边（不跨服务边界，非欠款）")
        lines.append("-" * 100)
        for src, tgt, _k, v in infras:
            lines.append(f"  {src}  ->  {tgt}   (import {v['count']} 次 / 涉及 {len(v['targets'])} 个类)")
        lines.append("")
        lines.append("=" * 100)
        lines.append("判定口径：DEBT 边 = 一旦拆微服务必须改成「Facade + HTTP 通道」的真实工作量单位；")
        lines.append("          REVERSE 边 = 契约模块反向依赖实现，属于必须先治理的架构违规。")
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
