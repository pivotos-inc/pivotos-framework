#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""备份的**纯逻辑层**（V3.1-H · B4）：保留份数轮转、完整性校验、SQL 语句切分。

为什么单独抽一层
----------------
这三个能力都属于「容易错、错了不可逆」的那一类：

  · **保留轮转**要删文件。删错一个备份比多留十个备份严重得多，所以它必须能被
    离线、可重复、零副作用地验证——依赖 pymysql 就意味着必须先有数据库才能测，
    那这类测试就永远不会有人跑。
  · **完整性校验**是全链路唯一能在「恢复之前」发现备份已经坏了的环节。
  · **SQL 切分**决定了恢复能不能执行完：用 `split(";")` 会在字符串字面量里的
    分号处直接断句（本仓的日志/sys_config 中有大量带分号的内容），
    这类错误的结果是「恢复跑到一半失败」，比不做恢复更糟。

本模块**刻意只依赖标准库**，任何 MySQL 客户端都不参与；`backup_mysql.py` 与
`restore_mysql.py` 都从这里取逻辑，避免两处各写一份然后慢慢漂移。
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

# 备份文件命名：<database>-<YYYYmmdd-HHMMSS>.sql，manifest 同名不同后缀
BACKUP_NAME = re.compile(r"^(?P<database>.+)-(?P<stamp>\d{8}-\d{6})\.sql$")
LEADING_COMMENT = re.compile(r"^\s*(?:--[^\n]*\n|#[^\n]*\n|/\*.*?\*/\s*)*", re.S)
MANIFEST_SUFFIX = ".manifest.json"


class RetentionError(RuntimeError):
    """轮转前置条件不满足时抛出（此时**一个文件都不删**）。"""


@dataclass(frozen=True)
class BackupSet:
    """一份完整的备份 = SQL 文件 + 同名 manifest。"""

    sql: Path
    manifest: Path | None
    database: str
    stamp: str

    @property
    def age_key(self) -> str:
        """排序键。时间戳是 %Y%m%d-%H%M%S，字典序 == 时间序，无需解析。"""
        return self.stamp


@dataclass(frozen=True)
class RetentionPlan:
    keep: list[BackupSet]
    delete: list[BackupSet]
    skipped: list[Path]
    incomplete: list[Path]


def discover_backups(directory: Path, database: str | None = None) -> RetentionPlan:
    """扫描目录里的备份集合。

    分成三堆：**完整**（SQL + manifest 都在）、**不完整**（只有 SQL 或只有 manifest，
    典型是备份跑到一半崩了留下的）、**无关文件**（日志、README 之类，这里完全不管）。

    `incomplete` 刻意**不参与轮转删除**：它们是事故现场，删了就再也没法还原当时到底
    发生了什么；但也**不占用保留份额**——不能让一批坏备份把好备份挤出去。
    """
    if not directory.is_dir():
        raise RetentionError(f"备份目录不存在或不是目录：{directory}")

    complete: list[BackupSet] = []
    incomplete: list[Path] = []
    recognized = 0
    for sql_path in sorted(directory.glob("*.sql")):
        matched = BACKUP_NAME.match(sql_path.name)
        if matched is None:
            continue  # 与备份无关的文件，一律不碰
        recognized += 1
        if database is not None and matched.group("database") != database:
            continue
        manifest = sql_path.with_suffix(MANIFEST_SUFFIX)
        item = BackupSet(sql=sql_path,
                         manifest=manifest if manifest.exists() else None,
                         database=matched.group("database"),
                         stamp=matched.group("stamp"))
        if item.manifest is None:
            incomplete.append(sql_path)
        else:
            complete.append(item)

    # manifest 比 SQL 多出来了（SQL 被手工删过？）也算不完整
    for manifest_path in sorted(directory.glob(f"*{MANIFEST_SUFFIX}")):
        sql = manifest_path.with_suffix("")
        sql = sql.with_suffix(".sql")
        if not sql.exists():
            incomplete.append(manifest_path)
        recognized += 1

    # 一个 recognize 不到的都没有 → 十有八九是目录写错了（`-o /backups` 写成 `-o /backup`）。
    # 这种情况下「什么都没删」看起来像成功，实则是备份从没被轮转过——必须报错。
    if recognized == 0:
        raise RetentionError(
            f"目录里没有任何符合 <database>-<YYYYmmdd-HHMMSS>.sql 命名的备份：{directory}。"
            f"拒绝执行轮转（很可能是目录参数写错，而『静默什么都没删』会被误当成成功）")

    complete.sort(key=lambda item: item.age_key, reverse=True)
    return RetentionPlan(keep=complete, delete=[], skipped=[], incomplete=incomplete)


def plan_retention(directory: Path, keep: int, database: str | None = None) -> RetentionPlan:
    """计算轮转计划。**本函数只读不写**，删除由 `apply_retention` 执行。"""
    if keep < 1:
        raise RetentionError(f"--retain 必须 >= 1（收到 {keep}）：0 会删光全部备份")
    plan = discover_backups(directory, database)
    plan.delete.clear()
    if len(plan.keep) <= keep:
        return plan
    survivors = plan.keep[:keep]
    plan = RetentionPlan(keep=survivors,
                         delete=plan.keep[keep:],
                         skipped=plan.skipped,
                         incomplete=plan.incomplete)
    return plan


def apply_retention(directory: Path, keep: int, database: str | None = None,
                    dry_run: bool = False) -> RetentionPlan:
    """执行轮转。默认 dry-run=True 的语义由调用方保证；这里 True 时一个文件都不删。"""
    plan = plan_retention(directory, keep, database)
    if dry_run:
        return plan
    for item in plan.delete:
        item.sql.unlink(missing_ok=True)
        if item.manifest is not None:
            item.manifest.unlink(missing_ok=True)
    return plan


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass(frozen=True)
class VerifyResult:
    ok: bool
    sql_file: Path
    manifest_file: Path | None
    expected_sha256: str | None
    actual_sha256: str | None
    problems: tuple[str, ...]

    def describe(self) -> str:
        if self.ok:
            return f"OK  {self.sql_file.name}  sha256 一致（{len(self.actual_sha256 or '')} 十六进制字符）"
        joined = "；".join(self.problems)
        return f"BAD {self.sql_file.name}  {joined}"


def verify_backup(sql_file: Path) -> VerifyResult:
    """按 manifest 里的 sha256 校验 SQL 文件。**这是恢复前的最后一道闸**。

    刻意接受「没有 manifest」也算失败：备份脚本是**先写 SQL 再写 manifest**的
    （见 backup_mysql.py 里 temp → sql → manifest 的顺序），所以「SQL 在但 manifest 不在」
    意味着备份没走到最后一步，这种文件拿去恢复等于碰运气。
    """
    manifest_file = sql_file.with_suffix(MANIFEST_SUFFIX)
    problems: list[str] = []

    if not sql_file.exists():
        return VerifyResult(False, sql_file, manifest_file if manifest_file.exists() else None,
                            None, None, ("SQL 文件不存在",))
    if not manifest_file.exists():
        return VerifyResult(False, sql_file, None, None, None,
                            ("缺少 manifest：备份很可能没跑完（先写 SQL 后写 manifest）",))

    try:
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return VerifyResult(False, sql_file, manifest_file, None, None,
                            (f"manifest 不是合法 JSON：{exc}",))

    expected = manifest.get("sha256")
    if not expected:
        return VerifyResult(False, sql_file, manifest_file, None, None,
                            ("manifest 里没有 sha256 字段",))

    actual = sha256_file(sql_file)
    if actual.lower() != str(expected).lower():
        problems.append(f"sha256 不一致：期望 {expected}，实际 {actual}")
    return VerifyResult(not problems, sql_file, manifest_file, str(expected), actual,
                        tuple(problems))


def iter_statements(sql: str) -> Iterable[str]:
    """把 dump 切成一个个完整语句——**按 SQL 词法，不是按分号**。

    为什么不能 `split(";")`：本仓的 sys_config、日志、AI 会话内容里都可能有 `;`
    （例如 retention 策略串、分号分隔的枚举值），一回写到库里再 dump 出来时，
    这些分号就出现在 INSERT 的字符串字面量中。用分号切会把一条 INSERT 砍成两截。

    这里处理了四种形态，缺一都会在真实数据上断句：
      1. `'...'` 与 `"..."` 内的内容（含 `\\` 转义与 SQL 标准的 `''` 双写转义）
      2. 反引号标识符（`` `a;b` `` 合法）
      3. `-- ` 与 `#` 行注释、`/* */` 块注释
      4. 末尾没有分号的最后一条语句（dump 结尾常这样）
    """
    buffer: list[str] = []
    index = 0
    length = len(sql)
    while index < length:
        char = sql[index]

        if char in ("'", '"', "`"):
            quote = char
            buffer.append(char)
            index += 1
            while index < length:
                current = sql[index]
                if current == "\\" and index + 1 < length:
                    buffer.append(sql[index:index + 2])
                    index += 2
                    continue
                if current == quote:
                    # SQL 里用双写表示一个字面量引号：'' 或 "" 或 ``
                    if index + 1 < length and sql[index + 1] == quote:
                        buffer.append(current * 2)
                        index += 2
                        continue
                    buffer.append(current)
                    index += 1
                    break
                buffer.append(current)
                index += 1
            continue

        if char == "-" and sql.startswith("--", index) and (index + 2 >= length or sql[index + 2] in " \t\r\n"):
            end = sql.find("\n", index)
            end = length if end < 0 else end
            buffer.append(sql[index:end])
            index = end
            continue

        if char == "#":
            end = sql.find("\n", index)
            end = length if end < 0 else end
            buffer.append(sql[index:end])
            index = end
            continue

        if char == "/" and sql.startswith("/*", index):
            end = sql.find("*/", index + 2)
            end = length if end < 0 else end + 2
            buffer.append(sql[index:end])
            index = end
            continue

        if char == ";":
            statement = "".join(buffer).strip()
            if statement:
                yield statement
            buffer.clear()
            index += 1
            continue

        buffer.append(char)
        index += 1

    tail = "".join(buffer).strip()
    if tail:
        yield tail


def strip_leading_comments(statement: str) -> str:
    """去掉语句前面的注释，拿到真正的语句开头。

    dump 的头部注释会被 `iter_statements` 并入紧随其后的第一条语句（MySQL 接受前置注释），
    于是 USE 那条语句在切分结果里长这样（前面挂着两行注释）：

        -- PivotOS MySQL logical backup
        -- Generated at: 2026-10-11T00:00:00Z
        USE <源库名>;

    直接看字符串开头（哪怕先 strip）都匹配不上 `use` 前缀，
    结果这条最危险的语句就会被当成普通语句执行掉。
    """
    return LEADING_COMMENT.sub("", statement, count=1).strip()


def classify_database_switch(statement: str) -> str | None:
    """判定语句是否为「库切换类」（USE / CREATE DATABASE / DROP DATABASE）。

    **为什么在显式指定目标库时必须跳过它们（本机 2026-10-11 实测，非推演）**

    `backup_mysql.py` 产出的 dump 里带有 `CREATE DATABASE <源库>` 与 `USE <源库>`，
    因为只有这样才支持「对着一台空实例直接灌 dump」装机。但恢复脚本是**先连上目标库
    再逐条执行**的：一旦执行到 `USE <源库>`，这条连接的当前库立刻被切走，
    后面上百条 CREATE/INSERT 就全部写进**源库**了，而每条语句都会【执行成功】。

    实测形态：`restore 执行 158 条语句 → tables=0 rows=0`，当时脚本还打印了 PASS。
    若备份来自测试环境、目标是生产实例，这一脚就把生产库重写成了测试数据。
    """
    head = strip_leading_comments(statement).lstrip("`").lower()
    for prefix, name in (("use ", "USE"), ("use`", "USE"),
                         ("create database", "CREATE DATABASE"),
                         ("create schema", "CREATE DATABASE"),
                         ("drop database", "DROP DATABASE"),
                         ("drop schema", "DROP DATABASE")):
        if head.startswith(prefix):
            return name
    return None


def now_utc_iso() -> str:
    """UTC 时间戳。

    ⚠️ 不要用 `datetime.utcnow()`：Python 3.12 起它已被标记 Deprecated，
    在 3.14 上跑会刷 DeprecationWarning，备份脚本的输出混入一堆噪音。
    """
    return datetime.now(timezone.utc).replace(microsecond=0, tzinfo=None).isoformat() + "Z"
