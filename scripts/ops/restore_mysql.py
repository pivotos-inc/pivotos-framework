#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MySQL 备份的恢复与演练（V3.1-H · B4）。

`backup_mysql.py` 只证明「能备份」；备份真正的价值在被恢复的那一次才兑现，
而历史上绝大多数备份事故都长得一样：**灾难发生时才发现备份早就坏了**。
所以这个脚本把「能不能恢复」变成可重复执行的动作，而不是靠人临场发挥。

三种模式（显式二选一，没有默认=no-op）：

    # 1) 只校验：不算、不连库，读完 sha256 就给结论。适合放进 CI 或备份后立刻跑
    python3 scripts/ops/restore_mysql.py --sql-file backup/pivotos_dev-20261011-120000.sql \\
            --mode verify

    # 2) 演练 drill：恢复进 **一次性数据库**（自动建、自动 DROP），与 manifest 对表数/行数，
    #    证明「这份备份真的能恢复出数据」，且不碰任何既有库
    PIVOTOS_MYSQL_PASSWORD='***' python3 scripts/ops/restore_mysql.py \\
            --sql-file backup/...sql --mode drill --host 175.24.176.176 --username root

    # 3) 真正恢复 restore：写进指定的目标库，需要 --yes；目标库非空时必须再加 --force
    python3 scripts/ops/restore_mysql.py --sql-file backup/...sql --mode restore \\
            --database pivotos_dev --yes [--force]

设计上的几条硬取舍：

1. **恢复前必须先过 sha256**（`--skip-verify` 才能绕过，且会显式打印警告）。
   一份校验不过的备份拿去恢复，最坏情况是恢复了一半失败，库处在半新半旧状态。
2. **drill 模式绝不写既有库**：一律新建 `<database>__drill_<timestamp>`，跑完即 DROP。
   演练的价值恰恰在于「随时敢跑」，一旦它可能碰到生产库就再也没人敢跑。
3. **restore 模式默认拒绝非空目标库**：灾难恢复的常见场景就是恢复到空库；对着一个还有
   65 张表的库恢复，往往是因为填错实例。真要覆盖就显式 `--force`。
4. **语句切分走 `backup_lib.iter_statements`（按 SQL 词法）而不是 `split(";")`**：
   dump 里的日志正文带上分号很常见，按分号切会把一条 INSERT 砍成两截。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import pymysql
except ImportError:  # pragma: no cover
    print("[restore-mysql] 缺少依赖 pymysql：python3 -m pip install pymysql", file=sys.stderr)
    raise SystemExit(2)

import backup_lib  # noqa: E402

DRILL_PREFIX = "__drill_"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="校验 / 演练 / 恢复 PivotOS MySQL 逻辑备份。")
    parser.add_argument("--sql-file", type=Path, required=True, help="备份 SQL 文件")
    parser.add_argument("--mode", choices=["verify", "drill", "restore"], required=True)
    parser.add_argument("--yes", action="store_true",
                        help="restore 模式的确认开关（不带则只打印计划）")
    parser.add_argument("--force", action="store_true",
                        help="restore 模式下允许写入非空目标库")
    parser.add_argument("--skip-verify", action="store_true",
                        help="跳过 sha256 校验（不建议）")
    parser.add_argument("--host",
                        default=os.getenv("PIVOTOS_MYSQL_HOST") or os.getenv("MYSQL_HOST"))
    parser.add_argument("--port", type=int,
                        default=int(os.getenv("PIVOTOS_MYSQL_PORT")
                                    or os.getenv("MYSQL_PORT") or 0) or None)
    parser.add_argument("--username",
                        default=os.getenv("PIVOTOS_MYSQL_USERNAME") or os.getenv("MYSQL_USER"))
    parser.add_argument("--password",
                        default=os.getenv("PIVOTOS_MYSQL_PASSWORD") or os.getenv("MYSQL_PASSWORD"))
    parser.add_argument("--database",
                        default=os.getenv("PIVOTOS_MYSQL_DATABASE") or os.getenv("MYSQL_DB"),
                        help="restore 模式的目标库；drill 模式下用作一次性库名前缀")
    parser.add_argument("--connect-timeout", type=int, default=10)
    return parser.parse_args()


def connect(args: argparse.Namespace, database: str | None = None):
    missing = [name for name, value in (("--host", args.host),
                                        ("--username", args.username)) if not value]
    if missing:
        raise SystemExit(f"[restore-mysql] 缺少参数：{'、'.join(missing)}")
    return pymysql.connect(host=args.host,
                           port=args.port or 3306,
                           user=args.username,
                           password=args.password or "",
                           database=database,
                           charset="utf8mb4",
                           connect_timeout=args.connect_timeout,
                           read_timeout=3600,
                           write_timeout=3600,
                           autocommit=False)


def existing_tables(connection, database: str) -> list[str]:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT TABLE_NAME FROM information_schema.TABLES "
            "WHERE TABLE_SCHEMA=%s AND TABLE_TYPE='BASE TABLE'", (database,))
        return [str(row[0]) for row in cursor.fetchall()]


# 判定「库切换类语句」的纯逻辑放在 backup_lib（只用标准库），
# 因为它必须能被离线反复验证：这是整个恢复流程里唯一会造成不可逆破坏的一步。


def execute_dump(args: argparse.Namespace, target_db: str) -> tuple[int, dict[str, int]]:
    """把 dump 恢复到 target_db。

    返回 (实际执行条数, {被跳过的语句类型: 条数})。
    """
    sql_text = args.sql_file.read_text(encoding="utf-8")
    statements = list(backup_lib.iter_statements(sql_text))
    print(f"[restore-mysql] {args.sql_file.name}：{len(statements)} 条语句待执行（目标库 `{target_db}`）")

    connection = connect(args, target_db)
    executed = 0
    skipped: dict[str, int] = {}
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET FOREIGN_KEY_CHECKS=0")
            for index, statement in enumerate(statements, start=1):
                switch = backup_lib.classify_database_switch(statement)
                if switch is not None:
                    skipped[switch] = skipped.get(switch, 0) + 1
                    continue
                try:
                    cursor.execute(statement)
                except Exception as exc:  # noqa: BLE001
                    connection.rollback()
                    preview = statement.replace("\n", " ")[:160]
                    raise RuntimeError(
                        f"第 {index}/{len(statements)} 条语句执行失败：{exc}\n"
                        f"  语句预览：{preview}") from exc
                executed += 1
            cursor.execute("SET FOREIGN_KEY_CHECKS=1")
        connection.commit()

        # 自检：执行完必须仍停在目标库上，否则说明上面漏跳了某个 USE
        with connection.cursor() as cursor:
            cursor.execute("SELECT DATABASE()")
            actual_db = str(cursor.fetchone()[0])
        if actual_db != target_db:
            connection.close()
            raise RuntimeError(
                f"恢复过程中连接被切走：期望停在 `{target_db}`，实际停在 `{actual_db}`。"
                f"这意味着语句写进了别的库（极可能是 dump 里的 USE 没被拦住），请在排除前禁止复用本脚本。")
        return executed, skipped
    finally:
        connection.close()


def compare_with_manifest(args: argparse.Namespace, target_db: str,
                          manifest: dict) -> tuple[int, int, list[str]]:
    """逐表核对（而不是只对总数）。

    为什么必须逐表：本机 2026-10-11 实测过一次「158 条语句全部执行成功、目标库 0 张表、
    脚本仍打印 PASS」的形态——只看总数时，`0 != 459` 被当成「可能因为目标库非空」的
    警告放行。逐表比对能让「有一张表没进来」这类事故立刻变红。
    """
    expected_tables = manifest.get("tables") or []
    connection = connect(args, target_db)
    try:
        actual_tables = set(existing_tables(connection, target_db))
        problems: list[str] = []

        missing = [t["name"] for t in expected_tables if t["name"] not in actual_tables]
        if missing:
            problems.append(f"{len(missing)} 张表恢复后不存在，例如：{missing[:5]}")

        rows = 0
        with connection.cursor() as cursor:
            for item in expected_tables:
                table = item["name"]
                if table in missing:
                    continue
                cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
                actual = int(cursor.fetchone()[0])
                rows += actual
                want = int(item.get("rowCount", -1))
                if want >= 0 and actual != want:
                    problems.append(f"{table} 行数不一致：期望 {want} 实际 {actual}")

        if not expected_tables:
            expected = manifest.get("tableCount")
            if expected is not None and int(expected) != len(actual_tables):
                problems.append(f"表数不一致：manifest={expected} 实测={len(actual_tables)}")
            with connection.cursor() as cursor:
                rows = 0
                for table in sorted(actual_tables):
                    cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
                    rows += int(cursor.fetchone()[0])

        return len(actual_tables), rows, problems
    finally:
        connection.close()


def mode_verify(args: argparse.Namespace, manifest: dict) -> int:
    result = backup_lib.verify_backup(args.sql_file)
    print(f"[restore-mysql] verify {result.describe()}")
    if result.ok:
        print(f"[restore-mysql] tables={manifest.get('tableCount')} "
              f"rows={manifest.get('rowCount')} db={manifest.get('database')}")
        return 0
    return 1


def mode_drill(args: argparse.Namespace, manifest: dict) -> int:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    base = args.database or manifest.get("database") or "pivot"
    drill_db = f"{base}{DRILL_PREFIX}{timestamp}"

    connection = connect(args)
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"CREATE DATABASE `{drill_db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        connection.commit()
    finally:
        connection.close()
    print(f"[restore-mysql] drill 目标库一次性：{drill_db}（跑完即 DROP）")

    try:
        executed, skipped = execute_dump(args, drill_db)
        if skipped:
            print(f"[restore-mysql] 刻意跳过 {skipped}（这些语句会把连接切回源库）")
        tables, rows, problems = compare_with_manifest(args, drill_db, manifest)
        print(f"[restore-mysql] drill 执行 {executed} 条语句 → tables={tables} rows={rows}")
        if problems:
            for problem in problems:
                print(f"[restore-mysql] ✗ {problem}", file=sys.stderr)
            return 1
        print(f"[restore-mysql] PASS drill tables={tables} rows={rows} 与 manifest 一致")
        return 0
    finally:
        connection = connect(args)
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"DROP DATABASE `{drill_db}`")
            connection.commit()
            print(f"[restore-mysql] drill 临时库已清理：{drill_db}")
        finally:
            connection.close()


def mode_restore(args: argparse.Namespace, manifest: dict) -> int:
    target = args.database
    if not target:
        raise SystemExit("[restore-mysql] restore 模式必须给 --database")

    connection = connect(args)
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s",
                (target,))
            exists = cursor.fetchone() is not None
        if not exists:
            with connection.cursor() as cursor:
                cursor.execute(
                    f"CREATE DATABASE `{target}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
            connection.commit()
            print(f"[restore-mysql] 目标库不存在，已创建：{target}")
        tables = existing_tables(connection, target)
    finally:
        connection.close()

    if tables and not args.force:
        raise SystemExit(
            f"[restore-mysql] 目标库 {target} 非空（{len(tables)} 张表），已拒绝执行。\n"
            f"  这是最常见的「实例填错」信号；确认要覆盖请显式加 --force。\n"
            f"  如果只是想验证备份能不能恢复，请用 --mode drill（不碰任何既有库）。")
    if not args.yes:
        print(f"[restore-mysql] 复述一遍将要发生的事（未加 --yes，未执行）：")
        print(f"  目标主机：{args.host}:{args.port or 3306}")
        print(f"  目标库　：{target}（现有 {len(tables)} 张表{'，将被覆盖' if tables else ''}）")
        print(f"  来源备份：{args.sql_file}  db={manifest.get('database')} "
              f"tables={manifest.get('tableCount')} rows={manifest.get('rowCount')}")
        print("  dump 里每张表都会先 DROP TABLE IF EXISTS 再 CREATE；目标库中不在 dump 里的表不会被删。")
        print("  确认执行请追加 --yes")
        return 0

    executed, skipped = execute_dump(args, target)
    if skipped:
        print(f"[restore-mysql] 刻意跳过 {skipped}（这些语句会把连接切回源库）")
    tables_after, rows_after, problems = compare_with_manifest(args, target, manifest)
    print(f"[restore-mysql] restore 执行 {executed} 条语句 → tables={tables_after} rows={rows_after}")
    if problems:
        for problem in problems:
            print(f"[restore-mysql] ✗ {problem}", file=sys.stderr)
        print("[restore-mysql] FAILED restore：数据没有按预期落进目标库", file=sys.stderr)
        return 1
    print("[restore-mysql] PASS restore 完成，逐表行数与 manifest 一致")
    return 0


def load_manifest(sql_file: Path) -> dict:
    manifest_path = sql_file.with_suffix(backup_lib.MANIFEST_SUFFIX)
    if not manifest_path.exists():
        raise SystemExit(f"[restore-mysql] 缺少 manifest：{manifest_path}"
                         f"（备份很可能没跑完；确要强制请用 --skip-verify 并自担风险）")
    return json.loads(manifest_path.read_text(encoding="utf-8"))


def main() -> int:
    args = parse_args()
    if not args.sql_file.exists():
        raise SystemExit(f"[restore-mysql] 备份文件不存在：{args.sql_file}")
    if args.mode == "verify":
        manifest = load_manifest(args.sql_file)
        if args.skip_verify:
            print("[restore-mysql] ⚠ 已 --skip-verify：本次不校验完整性")
            print(f"[restore-mysql] skip-verify tables={manifest.get('tableCount')} "
                  f"rows={manifest.get('rowCount')}")
            return 0
        return mode_verify(args, manifest)

    manifest = load_manifest(args.sql_file)
    if not args.skip_verify:
        result = backup_lib.verify_backup(args.sql_file)
        print(f"[restore-mysql] verify {result.describe()}")
        if not result.ok:
            print("[restore-mysql] 完整性校验未通过，拒绝恢复。"
                  "（绕过请加 --skip-verify）", file=sys.stderr)
            return 1

    if args.mode == "drill":
        return mode_drill(args, manifest)
    return mode_restore(args, manifest)


if __name__ == "__main__":
    raise SystemExit(main())
