#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PivotOS MySQL 逻辑备份。

目的：
- 在没有 mysql/mysqldump 客户端时，使用 pymysql 生成可恢复的 SQL。
- 通过一致性快照逐表输出 SHOW CREATE TABLE 与分批 INSERT。
- 备份结束后核对源表行数，并生成带 SHA-256 的 manifest。
- 可选按份数轮转（V3.1-H · B4）：`--retain N` 只保留最近 N 份成功备份。

凭据：
- 推荐从 PIVOTOS_MYSQL_URL / PIVOTOS_MYSQL_USERNAME /
  PIVOTOS_MYSQL_PASSWORD 环境变量读取。
- 也支持命令行参数；脚本不会打印密码。

⚠️ 保留策略的逻辑（谁能删、按什么顺序删、什么情况拒绝删）刻意放在 `backup_lib.py`
   并且**只用标准库**：删文件的规则必须能在没有数据库的机器上被反复验证，
   否则这条最不可逆的路径永远只能靠肉眼评审。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import unquote, urlparse

ROOT = os.path.dirname(os.path.abspath(__file__))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

try:
    import pymysql
    from pymysql.cursors import SSCursor
except ImportError:  # pragma: no cover - 由部署环境决定
    print(
        "[backup-mysql] 缺少依赖 pymysql，请先执行：python3 -m pip install pymysql",
        file=sys.stderr,
    )
    raise SystemExit(2)

# 轮转与校验的纯逻辑层（同目录，只用标准库）
import backup_lib  # noqa: E402


@dataclass(frozen=True)
class ConnectionOptions:
    host: str
    port: int
    username: str
    password: str
    database: str


@dataclass(frozen=True)
class TableBackup:
    name: str
    expected_rows: int
    dumped_rows: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="使用 pymysql 将 MySQL 数据库备份为 SHOW CREATE TABLE + 分批 INSERT SQL。",
    )
    parser.add_argument(
        "--url",
        default=os.getenv("PIVOTOS_MYSQL_URL"),
        help="JDBC/MySQL URL；默认读取 PIVOTOS_MYSQL_URL。",
    )
    parser.add_argument(
        "--host",
        default=os.getenv("PIVOTOS_MYSQL_HOST"),
        help="MySQL 主机；优先级高于 --url。",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.getenv("PIVOTOS_MYSQL_PORT", "0")) or None,
        help="MySQL 端口；优先级高于 --url，默认 3306。",
    )
    parser.add_argument(
        "--username",
        default=os.getenv("PIVOTOS_MYSQL_USERNAME"),
        help="MySQL 用户名；默认读取 PIVOTOS_MYSQL_USERNAME。",
    )
    parser.add_argument(
        "--password",
        default=os.getenv("PIVOTOS_MYSQL_PASSWORD"),
        help="MySQL 密码；推荐使用 PIVOTOS_MYSQL_PASSWORD，避免出现在进程列表。",
    )
    parser.add_argument(
        "--database",
        default=os.getenv("PIVOTOS_MYSQL_DATABASE"),
        help="数据库名；优先级高于 --url。",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="备份输出目录；不存在时自动创建。",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=500,
        help="每条 INSERT 包含的最大行数，默认 500。",
    )
    parser.add_argument(
        "--connect-timeout",
        type=int,
        default=10,
        help="连接超时秒数，默认 10。",
    )
    parser.add_argument(
        "--retain",
        type=int,
        default=0,
        help="只保留最近 N 份本库的备份（0 = 不清理，默认 0）。"
             "N<1 一律视为不清理；清理只删 <database>-<时间戳>.sql 及其 manifest。",
    )
    args = parser.parse_args()
    if args.batch_size < 1:
        parser.error("--batch-size 必须大于 0")
    if args.connect_timeout < 1:
        parser.error("--connect-timeout 必须大于 0")
    return args


def parse_mysql_url(raw_url: str | None) -> tuple[str | None, int | None, str | None]:
    if not raw_url:
        return None, None, None
    normalized = raw_url.strip()
    if normalized.startswith("jdbc:"):
        normalized = normalized[5:]
    parsed = urlparse(normalized)
    if parsed.scheme not in {"mysql", "mariadb"}:
        raise ValueError("--url 必须是 jdbc:mysql://、mysql:// 或 mariadb:// 地址")
    database = unquote(parsed.path.lstrip("/")) or None
    return parsed.hostname, parsed.port, database


def resolve_connection(args: argparse.Namespace) -> ConnectionOptions:
    url_host, url_port, url_database = parse_mysql_url(args.url)
    host = args.host or url_host
    port = args.port or url_port or 3306
    database = args.database or url_database
    missing = [
        name
        for name, value in (
            ("host（--host 或 PIVOTOS_MYSQL_URL）", host),
            ("username（--username 或 PIVOTOS_MYSQL_USERNAME）", args.username),
            ("password（--password 或 PIVOTOS_MYSQL_PASSWORD）", args.password),
            ("database（--database 或 PIVOTOS_MYSQL_URL）", database),
        )
        if value is None
    ]
    if missing:
        raise ValueError("缺少连接参数：" + "、".join(missing))
    if not 1 <= port <= 65535:
        raise ValueError("MySQL 端口必须在 1~65535 之间")
    return ConnectionOptions(
        host=str(host),
        port=port,
        username=str(args.username),
        password=str(args.password),
        database=str(database),
    )


def quote_identifier(value: str) -> str:
    return "`" + value.replace("`", "``") + "`"


def iter_batches(cursor: SSCursor, batch_size: int) -> Iterable[list[tuple[Any, ...]]]:
    while True:
        rows = cursor.fetchmany(batch_size)
        if not rows:
            return
        yield list(rows)


def sql_value(connection: pymysql.Connection, value: Any) -> str:
    return connection.escape(value)


def write_insert(
    output: Any,
    connection: pymysql.Connection,
    table: str,
    columns: list[str],
    rows: list[tuple[Any, ...]],
) -> None:
    column_sql = ", ".join(quote_identifier(column) for column in columns)
    output.write(f"INSERT INTO {quote_identifier(table)} ({column_sql}) VALUES\n")
    value_rows = []
    for row in rows:
        value_rows.append("(" + ", ".join(sql_value(connection, value) for value in row) + ")")
    output.write(",\n".join(value_rows))
    output.write(";\n")


# 校验与轮转复用 backup_lib 的实现——同一个 sha256 若在两个脚本里各写一份，
# 迟早会出现「备份这边算得对、校验那边算得错」这种最难查的形态。


def create_connection(options: ConnectionOptions, timeout: int) -> pymysql.Connection:
    return pymysql.connect(
        host=options.host,
        port=options.port,
        user=options.username,
        password=options.password,
        database=options.database,
        charset="utf8mb4",
        connect_timeout=timeout,
        read_timeout=max(timeout, 60),
        write_timeout=max(timeout, 60),
        autocommit=False,
    )


def load_schema_metadata(connection: pymysql.Connection, database: str) -> tuple[str, str]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
            FROM information_schema.SCHEMATA
            WHERE SCHEMA_NAME = %s
            """,
            (database,),
        )
        row = cursor.fetchone()
    if row is None:
        raise RuntimeError(f"数据库不存在：{database}")
    return str(row[0]), str(row[1])


def load_tables(connection: pymysql.Connection, database: str) -> list[str]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT TABLE_NAME
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = %s AND TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_NAME
            """,
            (database,),
        )
        return [str(row[0]) for row in cursor.fetchall()]


def backup_table(
    connection: pymysql.Connection,
    output: Any,
    table: str,
    batch_size: int,
) -> TableBackup:
    quoted_table = quote_identifier(table)
    with connection.cursor() as cursor:
        cursor.execute(f"SHOW CREATE TABLE {quoted_table}")
        create_row = cursor.fetchone()
        if create_row is None:
            raise RuntimeError(f"无法读取表结构：{table}")
        create_sql = str(create_row[1]).rstrip(";")
        cursor.execute(f"SELECT COUNT(*) FROM {quoted_table}")
        expected_rows = int(cursor.fetchone()[0])

    output.write(f"\n-- Table: {table}; expected rows: {expected_rows}\n")
    output.write(f"DROP TABLE IF EXISTS {quoted_table};\n")
    output.write(create_sql + ";\n")

    dumped_rows = 0
    with connection.cursor(SSCursor) as cursor:
        cursor.execute(f"SELECT * FROM {quoted_table}")
        columns = [str(description[0]) for description in cursor.description or []]
        for rows in iter_batches(cursor, batch_size):
            write_insert(output, connection, table, columns, rows)
            dumped_rows += len(rows)

    if dumped_rows != expected_rows:
        raise RuntimeError(
            f"表 {table} 行数自检失败：源库 {expected_rows}，已写入 {dumped_rows}"
        )
    return TableBackup(table, expected_rows, dumped_rows)


def backup_database(args: argparse.Namespace, options: ConnectionOptions) -> tuple[Path, Path, int, int]:
    args.output_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    sql_path = args.output_dir / f"{options.database}-{timestamp}.sql"
    temp_path = sql_path.with_suffix(".sql.tmp")
    manifest_path = sql_path.with_suffix(".manifest.json")
    connection = create_connection(options, args.connect_timeout)
    backups: list[TableBackup] = []

    try:
        charset, collation = load_schema_metadata(connection, options.database)
        tables = load_tables(connection, options.database)
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")

        with temp_path.open("w", encoding="utf-8", newline="\n") as output:
            os.chmod(temp_path, 0o600)
            output.write("-- PivotOS MySQL logical backup\n")
            output.write(f"-- Generated at: {datetime.now(timezone.utc).isoformat()}\n")
            output.write(f"-- Database: {options.database}\n")
            output.write("SET NAMES utf8mb4;\n")
            output.write("SET FOREIGN_KEY_CHECKS=0;\n")
            output.write(
                f"CREATE DATABASE IF NOT EXISTS {quote_identifier(options.database)} "
                f"CHARACTER SET {charset} COLLATE {collation};\n"
            )
            output.write(f"USE {quote_identifier(options.database)};\n")
            for index, table in enumerate(tables, start=1):
                backup = backup_table(connection, output, table, args.batch_size)
                backups.append(backup)
                print(
                    f"[backup-mysql] [{index}/{len(tables)}] {table}: "
                    f"rows={backup.dumped_rows}"
                )
            output.write("\nSET FOREIGN_KEY_CHECKS=1;\n")

        connection.rollback()
        temp_path.replace(sql_path)
        os.chmod(sql_path, 0o600)
        total_rows = sum(item.dumped_rows for item in backups)
        manifest = {
            "format": "pivotos-mysql-logical-backup-v1",
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "database": options.database,
            "tableCount": len(backups),
            "rowCount": total_rows,
            "sha256": backup_lib.sha256_file(sql_path),
            "sqlFile": sql_path.name,
            "tables": [
                {"name": item.name, "rowCount": item.dumped_rows}
                for item in backups
            ],
        }
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        os.chmod(manifest_path, 0o600)
        return sql_path, manifest_path, len(backups), total_rows
    except Exception:
        connection.rollback()
        temp_path.unlink(missing_ok=True)
        raise
    finally:
        connection.close()


def main() -> int:
    args = parse_args()
    try:
        options = resolve_connection(args)
        sql_path, manifest_path, table_count, row_count = backup_database(args, options)
    except (ValueError, RuntimeError, pymysql.MySQLError, OSError,
            backup_lib.RetentionError) as exc:
        print(f"[backup-mysql] FAILED: {exc}", file=sys.stderr)
        return 1

    print(
        f"[backup-mysql] PASS tables={table_count} rows={row_count} "
        f"sql={sql_path} manifest={manifest_path}"
    )

    # 轮转放在**备份成功之后**：万一这一步失败也不能污染「备份有没有成功」的结论。
    # 且只对本库(database)生效，避免一个多库共用的备份目录被别的库的轮转策略波及。
    if args.retain and args.retain > 0:
        try:
            plan = backup_lib.apply_retention(args.output_dir, args.retain,
                                              database=options.database, dry_run=False)
        except backup_lib.RetentionError as exc:
            print(f"[backup-mysql] 轮转未执行（备份本身已成功）：{exc}", file=sys.stderr)
            return 0
        if plan.delete:
            for item in plan.delete:
                print(f"[backup-mysql] rotate removed {item.sql.name}")
        print(f"[backup-mysql] rotate keep={len(plan.keep)} removed={len(plan.delete)} "
              f"incomplete-skipped={len(plan.incomplete)} (retain={args.retain})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

