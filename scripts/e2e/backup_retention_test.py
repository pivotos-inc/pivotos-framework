#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""备份保留轮转 / 完整性校验 / SQL 切分 的离线自检（V3.1-H · B4）。

**这个脚本刻意不连任何数据库**：00:00 起不出 MYSQL_HOST 的 CI 环境、以及任何人的
笔记本上都能跑。它守的是「备份体系最不可逆的那一半」——删文件的规则和恢复时怎么断句。

    python3 scripts/e2e/backup_retention_test.py

覆盖：
  R 组  保留份数轮转（只删该删的、危险参数必须拒、坏备份与无关文件不占份额）
  V 组  manifest 完整性校验（一致 / 篡改 / 缺 manifest / manifest 坏 JSON）
  S 组  SQL 语句切分（字符串里的分号 / 注释里的分号 / 反引号 / 双写引号 / 末尾无分号）
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
OPS_DIR = ROOT / "scripts" / "ops"

# ⚠️ 这里不能用 importlib.util.spec_from_file_location + exec_module：
# Python 3.14 的 dataclasses 在类装饰阶段会去 sys.modules[cls.__module__].__dict__ 查注解，
# 而 exec_module 之前该模块还没登记进 sys.modules → AttributeError: 'NoneType' object has no attribute '__dict__'。
# 直接把目录塞进 sys.path 正常 import 即可。
sys.path.insert(0, str(OPS_DIR))
_lib = importlib.import_module("backup_lib")

results: list[tuple[str, bool, str]] = []


def check(tag: str, cond: bool, msg: str) -> None:
    results.append((tag, bool(cond), msg))
    print(("PASS " if cond else "FAIL ") + f"{tag} {msg}", flush=True)
    if not cond:
        raise AssertionError(f"{tag} 断言失败：{msg}")


def write_backup(directory: Path, database: str, stamp: str, body: str = "SELECT 1;") -> Path:
    sql = directory / f"{database}-{stamp}.sql"
    sql.write_text(body, encoding="utf-8")
    (directory / f"{database}-{stamp}.manifest.json").write_text(
        json.dumps({"database": database, "sha256": _lib.sha256_file(sql)}, indent=2),
        encoding="utf-8")
    return sql


# ───────────────────────── R 组：保留轮转 ─────────────────────────

def group_retention() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        # R-1 基本形态：5 份留 2 → 删掉最老的 3 份，保留最新的 2 份
        stamps = ["20261001-010000", "20261002-010000", "20261003-010000",
                  "20261004-010000", "20261005-010000"]
        for stamp in stamps:
            write_backup(root, "pivotos_dev", stamp)
        plan = _lib.plan_retention(root, keep=2)
        check("R-1", len(plan.delete) == 3 and len(plan.keep) == 2,
              f"5 份保留 2：应删 3 份，实测删 {len(plan.delete)} 保 {len(plan.keep)}")
        check("R-2", [p.stamp for p in plan.keep] == ["20261005-010000", "20261004-010000"],
              f"保留的必须是最新两天的：{[p.stamp for p in plan.keep]}")

        # R-3 计划阶段不得删任何文件（真正删除只在 apply 且 dry_run=False）
        check("R-3", len(list(root.glob("*.sql"))) == 5,
              "plan_retention 只读不写：计划阶段 5 个 SQL 文件必须都还在")

        # R-4 dry_run 也必须什么都不删
        dry = _lib.apply_retention(root, keep=2, dry_run=True)
        check("R-4", len(dry.delete) == 3 and len(list(root.glob("*.sql"))) == 5,
              "dry-run 返回同样的删除计划，但磁盘上一份都不动")

        # R-5 真删之后：只剩 2 份 SQL + 2 份 manifest
        _lib.apply_retention(root, keep=2, dry_run=False)
        sqls = sorted(p.name for p in root.glob("*.sql"))
        manifests = sorted(p.name for p in root.glob("*.manifest.json"))
        check("R-5", len(sqls) == 2 and len(manifests) == 2,
              f"真轮转后 SQL 与 manifest 成对剩下 2 份：sql={len(sqls)} manifest={len(manifests)}")

        # R-6 份数不够时不许删
        before = len(list(root.glob("*.sql")))
        _lib.apply_retention(root, keep=5, dry_run=False)
        check("R-6", len(list(root.glob("*.sql"))) == before,
              "保留份数 >= 现有份数：一份都不删")


def group_retention_guards() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        # R-7 keep=0 必须直接拒绝（0 会把备份删光，是最容易写错的一个参数）
        write_backup(root, "pivotos_dev", "20261001-010000")
        try:
            _lib.plan_retention(root, keep=0)
            check("R-7", False, "keep=0 必须抛 RetentionError")
        except _lib.RetentionError:
            check("R-7", True, "keep=0 被拒绝（RetentionError），且不会误删既有备份")

        # R-8 目录里没有任何可识别备份 → 拒绝，宁可报错也不「删了个寂寞」
        empty = Path(tmp) / "empty"
        empty.mkdir()
        (empty / "README.md").write_text("不是备份文件", encoding="utf-8")
        try:
            _lib.apply_retention(empty, keep=1, dry_run=False)
            check("R-8", False, "无可识别备份时必须拒绝执行")
        except _lib.RetentionError:
            check("R-8", True, "目录里没有可识别的备份集合 → 拒绝执行（不静默成功）")

        # R-9 不完整的备份（只有 SQL 没有 manifest）不占保留份额，也不许被删。
        # 注意用独立子目录：上面 R-7 已经往 root 里写过一份，混着数会算错份额。
        sub = Path(tmp) / "r9"
        sub.mkdir()
        write_backup(sub, "pivotos_dev", "20261010-010000")
        write_backup(sub, "pivotos_dev", "20261011-010000")
        (sub / "pivotos_dev-20261012-010000.sql").write_text("半成品", encoding="utf-8")
        plan = _lib.plan_retention(sub, keep=2)
        check("R-9", len(plan.keep) == 2 and not plan.delete and len(plan.incomplete) == 1,
              f"坏备份不占份额也不可删：keep={len(plan.keep)} delete={len(plan.delete)} "
              f"incomplete={len(plan.incomplete)}")

        # R-10 保留份额内的备份即使「疑似损坏」也必须留着（它们仍是最新可查的东西）
        check("R-10", all(p.manifest is not None for p in plan.keep),
              "保留集合里的两份都有 manifest（出现在 keep 而非 incomplete）")


# ───────────────────────── V 组：完整性校验 ─────────────────────────

def group_verify() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        sql = write_backup(root, "pivotos_dev", "20261001-010000", body="INSERT INTO t VALUES(1);")
        ok = _lib.verify_backup(sql)
        check("V-1", ok.ok, f"完好的备份应通过校验：{ok.describe()}")

        # V-2 篡改一个字节必须被抓到（这是「没校验就恢复」唯一会踩的雷）
        sql.write_text("INSERT INTO t VALUES(2);", encoding="utf-8")
        bad = _lib.verify_backup(sql)
        check("V-2", not bad.ok and any("不一致" in p for p in bad.problems),
              f"内容被改后必须失败：{bad.describe()}")
        check("V-3", bad.expected_sha256 and bad.actual_sha256 and bad.expected_sha256 != bad.actual_sha256,
              "失败报告里必须同时给出期望值与实际值，否则没法人工判断差距在哪")

        # V-4 没有 manifest = 备份没跑完 → 判失败（而不是当作「没记录就把 VOID 通过」）
        orphan = root / "pivotos_dev-20261002-010000.sql"
        orphan.write_text("SELECT 1;", encoding="utf-8")
        missing = _lib.verify_backup(orphan)
        check("V-4", not missing.ok, f"缺 manifest 必须判失败：{missing.describe()}")

        # V-5 manifest 不是合法 JSON → 也必须判失败且指明原因
        broken = root / "pivotos_dev-20261003-010000.sql"
        broken.write_text("SELECT 1;", encoding="utf-8")
        (root / "pivotos_dev-20261003-010000.manifest.json").write_text("{不是 json", encoding="utf-8")
        broken_result = _lib.verify_backup(broken)
        check("V-5", not broken_result.ok and any("JSON" in p for p in broken_result.problems),
              f"manifest 坏 JSON 必须判失败：{broken_result.describe()}")

        # V-6 文件不存在 → 失败而不是抛异常（恢复前批量校验不该崩在中途）
        vanished = _lib.verify_backup(root / "pivotos_dev-20991231-235959.sql")
        check("V-6", not vanished.ok, "文件不存在时应给出失败结论而不是抛栈")


# ───────────────────────── S 组：SQL 切分 ─────────────────────────

def group_split() -> None:
    # S-1 字符串字面量里的分号不能被当成语句结束
    sql = "INSERT INTO sys_config VALUES (1,'a;b;c');\nINSERT INTO sys_config VALUES (2,'x');"
    parts = list(_lib.iter_statements(sql))
    check("S-1", len(parts) == 2,
          f"字符串里的分号不得断句：期望 2 条语句，实测 {len(parts)} 条 → {[p[:40] for p in parts]}")

    # S-2 注释里的分号同理（dump 头部有很多 "-- Generated at: ..." 之类）
    sql = ("-- 这是一条注释; 里面有分号\nINSERT INTO t VALUES (1);\n"
           "# 井号注释; 也有分号\nINSERT INTO t VALUES (2);")
    parts = list(_lib.iter_statements(sql))
    check("S-2", len(parts) == 2 and parts[1].endswith("(2)"),
          f"注释里的分号不得断句：实测 {len(parts)} 条 → {[p[:40] for p in parts]}")

    # S-3 块注释
    parts = list(_lib.iter_statements("/* 块注释; 带分号 */ INSERT INTO t VALUES (1);"))
    check("S-3", len(parts) == 1 and "块注释" in parts[0],
          f"块注释整体必须是语句的一部分而非独立语句：{[p[:50] for p in parts]}")

    # S-4 SQL 标准的双写引号转义
    parts = list(_lib.iter_statements("INSERT INTO t VALUES ('it''s; here');"))
    check("S-4", len(parts) == 1 and parts[0].endswith("('it''s; here')"),
          f"双写单引号不应提前结束字符串：{[p for p in parts]}")

    # S-5 反斜杠转义（mysqldump 默认输出）
    parts = list(_lib.iter_statements("INSERT INTO t VALUES ('esc\\'; not end');"))
    check("S-5", len(parts) == 1, f"反斜杠转义引号后不应断句：{[p for p in parts]}")

    # S-6 反引号标识符里带分号
    parts = list(_lib.iter_statements("INSERT INTO `we;ird` VALUES (1);"))
    check("S-6", len(parts) == 1, f"反引号标识符内的分号不得断句：{[p for p in parts]}")

    # S-7 最后一条没有分号也必须产出（dump 尾部常见）
    parts = list(_lib.iter_statements("INSERT INTO t VALUES (1);\nINSERT INTO t VALUES (2)"))
    check("S-7", len(parts) == 2 and parts[1].endswith("(2)"),
          f"末尾无分号的语句不能丢：{[p for p in parts]}")

    # S-8 空语句（连续分号）应当被跳过，否则执行时会报空查询
    parts = list(_lib.iter_statements("INSERT INTO t VALUES (1);;\n;\nINSERT INTO t VALUES (2);"))
    check("S-8", len(parts) == 2, f"连续分号产生的空语句必须被丢弃：{[p for p in parts]}")

    # S-9 真实形态：整段 dump 头部 + 多表
    sample = """-- PivotOS MySQL logical backup
-- Generated at: 2026-10-11T00:00:00+00:00
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;
INSERT INTO sys_config VALUES (1,'模式;a','b'),(2,'c','d');
INSERT INTO sys_log VALUES (1,'2026-10-11 10:00:00','请求失败;已重试');
SET FOREIGN_KEY_CHECKS=1;
"""
    parts = list(_lib.iter_statements(sample))
    # 头部两行注释会被并入紧随其后的第一条语句执行（MySQL 接受前置注释），因此是 5 条而不是 6 条。
    # 关键不是条数本身，而是两条 INSERT 各自完整、且注释内容没有丢。
    check("S-9", len(parts) == 5,
          f"整段 dump 切成 {len(parts)} 条（头部注释并入首条，属预期）")
    check("S-10", parts[0].startswith("-- PivotOS MySQL logical backup")
          and "SET NAMES utf8mb4" in parts[0],
          "头部注释并入紧随的首条语句，内容不丢")
    inserts = [p for p in parts if p.startswith("INSERT INTO")]
    check("S-11", len(inserts) == 2 and all(p.count("(") == p.count(")") for p in inserts),
          f"两条 INSERT 各自完整且括号配平：{[p[:45] for p in inserts]}")


def group_switch() -> None:
    """T 组：库切换语句必须被认出来（少了这一步，恢复会写错库且每条都「成功」）。"""
    # T-1 最常见的形态：dump 头部两条注释 + USE
    case = "-- PivotOS MySQL logical backup\n-- Generated at: 2026-10-11T00:00:00Z\nUSE `pivotos_dev`"
    check("T-1", _lib.classify_database_switch(case) == "USE",
          "头部挂着注释的 USE 必须被识别（真实 dump 就是这个形态）")

    # T-2 干净形态
    check("T-2", _lib.classify_database_switch("USE `pivotos_dev`") == "USE",
          "不带注释的 USE 也要被识别")
    check("T-3", _lib.classify_database_switch("use pivotos_dev") == "USE",
          "小写 use 也要被识别")
    check("T-4", _lib.classify_database_switch("CREATE DATABASE IF NOT EXISTS `x`") == "CREATE DATABASE",
          "CREATE DATABASE 必须被识别（否则会在目标实例上多建一个源同名库）")
    check("T-5", _lib.classify_database_switch("CREATE SCHEMA `x`") == "CREATE DATABASE",
          "CREATE SCHEMA 是 CREATE DATABASE 的同义写法")

    # T-6 反向：普通语句一个都不能被误判
    innocents = ["SET NAMES utf8mb4", "SET FOREIGN_KEY_CHECKS=0",
                 "INSERT INTO t VALUES (1)", "CREATE TABLE `t` (id int)",
                 "DROP TABLE IF EXISTS `t`", "SELECT DATABASE()"]
    wrong = [s for s in innocents if _lib.classify_database_switch(s) is not None]
    check("T-6", not wrong,
          f"普通语句不得被误判为库切换（误判会让表建不出来）：{wrong}")

    # T-7 端到端：真实 dump 片段切完之后，USE 必须仍能被认出来
    sample = ("-- PivotOS MySQL logical backup\n"
              "-- Generated at: 2026-10-11T00:00:00Z\n"
              "-- Database: pivotos_dev\n"
              "SET NAMES utf8mb4;\n"
              "CREATE DATABASE IF NOT EXISTS `pivotos_dev` CHARACTER SET utf8mb4;\n"
              "USE `pivotos_dev`;\n"
              "INSERT INTO t VALUES (1,'a;b');")
    kinds = [_lib.classify_database_switch(s) for s in _lib.iter_statements(sample)]
    check("T-7", kinds.count("USE") == 1 and kinds.count("CREATE DATABASE") == 1
          and kinds.count(None) == 2,
          f"整段切完后库切换语句仍需被全部识别：{kinds}")


def main() -> int:
    print("=" * 78)
    print("备份保留轮转 / 完整性 / SQL 切分 离线自检（不连数据库）")
    print("=" * 78)
    failed = 0
    for group in (group_retention, group_retention_guards, group_verify, group_split,
                  group_switch):
        try:
            group()
        except AssertionError as exc:
            print(f"ERROR {exc}", flush=True)
            failed += 1
        except Exception as exc:  # noqa: BLE001
            print(f"ERROR {group.__name__} 异常：{exc}", flush=True)
            failed += 1
    passed = sum(1 for _, ok, _ in results if ok)
    print("-" * 78)
    print(f"{passed}/{len(results)} 通过，失败组 {failed} 个")
    return 1 if failed or passed != len(results) else 0


if __name__ == "__main__":
    sys.exit(main())
