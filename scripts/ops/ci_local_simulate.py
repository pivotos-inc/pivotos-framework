#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CI 同构本地自证 + 产物归档门禁（v3.1.0 P1-B5）。

为什么需要这个脚本
------------------
本仓 CI **从未在 GitHub Actions 上真绿过**。而一段 yml 提交上去之后，是否真的能跑、
产物是否真的收齐，在按下 push 的那一刻是**没有任何证据**的 —— runner 上的成败既没法
在本地复现，也没事后取证的能力。官方的本地方案是 act，但 act 依赖 docker daemon，
本机并不总是可用（2026-10-12 实测 daemon 未启动）。

所以这里的做法是**同构自证**：本脚本解析 `.github/workflows/ci.yml`，在本地按同样的
步骤顺序真跑 `run` 命令，并用等价实现落地 `upload-artifact` 的产物，然后校验这些东西
真的存在且可用。它证明的是「steps 的顺序、命令、归档路径、归档内容自洽」，
**不证明 runner 侧的 services / cache / 权限行为** —— 这一点在输出里会反复讲。

两种模式
--------
    --lint-only   静态门禁（快，可在 CI 里当检查步骤）
    --simulate    真跑 run 步骤 + 真落地归档（慢，本地自证用）

静态门禁清单（每条都对应一个真实缺陷形态，不是凑数）
--------------------------------------------------
    S1  ci.yml 能被解析出至少一个 job（解析不了就说明格式越界了，必须响）
    S2  uses 必须钉版本（@vN 或 40 位 sha）；@master/@main 是供应链风险
    A1  每个 job 至少一个 upload-artifact —— 没有归档，失败现场随 runner 一起消失
    A2  归档步骤必须 `if: always()` —— 否则**跑挂的那一次正好没有产物**，最需要时拿不到
    A3  retention-days 必须显式设置（缺省 90 天，白占配额）
    A4  if-no-files-found 必须为 error —— 缺省是 warn，路径写错会「静默归档了一个空包」
    A5  artifact name 在同一上下文内唯一；带 matrix 的 job，name 必须含 matrix 变量
        （否则矩阵各格互相覆盖，最后只剩一个）
    A6  path 每一行必须至少匹配到一个文件 —— **运行态门禁**：只有 --simulate 跑完 run 之后才查；
        lint-only 不跑 run、产物目录不存在，查它必然假红（实测 FAIL 6，V3.1-J 遗留④，本批修）
    A7  run 步骤里引用的 scripts/** 文件必须存在（防止 yml 写了不存在的脚本）
    A8  禁止已废弃的 actions/upload-artifact@v3 及其以下
    A9  fat jar 这类大件，retention-days > 7 会告警（配额成本）

用法
----
    python3 scripts/ops/ci_local_simulate.py --lint-only
    python3 scripts/ops/ci_local_simulate.py --simulate --job compat-matrix
    python3 scripts/ops/ci_local_simulate.py --simulate --skip-maven   # 已构建过，只验归档
    python3 scripts/ops/ci_local_simulate.py --simulate --dry-run      # 只打印将要做什么
    python3 scripts/ops/ci_local_simulate.py --simulate --json out.json

退出码：0 = 通过；1 = 存在 FAIL；2 = 输入不可用。
"""

from __future__ import annotations

import argparse
import glob as globmod
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CI_YML = REPO_ROOT / ".github" / "workflows" / "ci.yml"
# ⚠️ 不能放在 target/ 下：各模块的 `mvn clean` 会把 root 的 target 一并删掉，归档就没了。
# （CI 里两个 job 各自有独立工作区所以不会互相覆盖，本地同一工作区跑两个 job 会。）
DEFAULT_OUT = REPO_ROOT / "build" / "ci-artifacts"

OK = "\033[32m" if sys.stdout.isatty() else ""
RED = "\033[31m" if sys.stdout.isatty() else ""
YEL = "\033[33m" if sys.stdout.isatty() else ""
DIM = "\033[2m" if sys.stdout.isatty() else ""
RESET = "\033[0m" if sys.stdout.isatty() else ""

KNOWN_ACTIONS = {
    "actions/checkout",
    "actions/setup-java",
    "actions/cache",
    "actions/upload-artifact",
}

# ---------------------------------------------------------------- 解析（ci.yml 子集）

@dataclass
class Step:
    index: int
    name: str = ""
    uses: str | None = None
    run: str | None = None
    if_expr: str | None = None
    with_block: dict[str, str] = field(default_factory=dict)
    env: dict[str, str] = field(default_factory=dict)

    def label(self) -> str:
        base = self.name or (self.uses or (self.run or "").strip().splitlines()[0][:60])
        return base.strip() or f"step#{self.index}"


@dataclass
class Job:
    job_id: str
    name: str = ""
    runs_on: str = ""
    matrix: dict[str, list[str]] = field(default_factory=dict)
    steps: list[Step] = field(default_factory=list)
    services: list[str] = field(default_factory=list)
    job_env: dict[str, str] = field(default_factory=dict)


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def parse_block_scalar(lines: list[str], i: int, key_indent: int) -> tuple[str, int]:
    """读 run: | 这类多行块标量。返回 (内容, 下一个行号)。"""
    out: list[str] = []
    i += 1
    while i < len(lines):
        raw = lines[i]
        if not raw.strip():
            out.append("")
            i += 1
            continue
        if indent_of(raw) <= key_indent:
            break
        out.append(raw[key_indent + 2:] if len(raw) > key_indent + 2 else "")
        i += 1
    # 去掉尾部空行，保留中间空行
    while out and not out[-1].strip():
        out.pop()
    return "\n".join(out), i


def parse_ci(text: str) -> dict[str, Job]:
    """解析 ci.yml。刻意只覆盖 GitHub Actions 的常见结构。

    解析不出来就抛异常 —— 「看不懂的格式」必须响，不能当作「没有这条检查」。
    """
    lines = text.splitlines()
    # 只从顶层 `jobs:` 之后开始解析。早期版本从文件头开始，会把 `on:` 下面的
    # push / pull_request 当成 job（它们同样是缩进 2 且以冒号结尾）。
    start = None
    for idx, raw in enumerate(lines):
        if indent_of(raw) == 0 and raw.strip() == "jobs:":
            start = idx + 1
            break
    if start is None:
        raise ValueError("ci.yml 里找不到顶层 jobs: 块")

    jobs: dict[str, Job] = {}
    job: Job | None = None
    step: Step | None = None
    i = start
    step_seq = 0

    while i < len(lines):
        raw = lines[i]
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            i += 1
            continue
        ind = indent_of(raw)

        # ---- job id：缩进 2 ----
        if ind == 2 and re.fullmatch(r"[\w-]+:", stripped):
            job = Job(job_id=stripped[:-1])
            jobs[job.job_id] = job
            step = None
            i += 1
            continue

        if job is None:
            i += 1
            continue

        # ---- job 级：缩进 4 ----
        if ind == 4:
            m = re.match(r"^([\w-]+):\s*(.*)$", stripped)
            if not m:
                i += 1
                continue
            key, val = m.group(1), m.group(2).strip()
            if key == "steps":
                i += 1
                continue
            if key == "services":
                i += 1
                while i < len(lines) and (not lines[i].strip() or indent_of(lines[i]) > 4):
                    if indent_of(lines[i]) == 6 and lines[i].strip().endswith(":"):
                        job.services.append(lines[i].strip()[:-1])
                    i += 1
                continue
            if key == "strategy":
                # matrix 挂在 strategy 之下（缩进 6），不在 job 直属层。
                # 早期版本假设 matrix 是缩进 4，结果 job.matrix 恒为空 →
                # A5 被短路形同虚设，且 "${{ matrix.* }}" 在 simulate 里原样进了命令。
                i += 1
                while i < len(lines) and (not lines[i].strip() or indent_of(lines[i]) > 4):
                    if lines[i].strip() == "matrix:":
                        mi = indent_of(lines[i])
                        i += 1
                        while i < len(lines) and (not lines[i].strip() or indent_of(lines[i]) > mi):
                            mm = re.match(r"^\s+([\w-]+):\s*\[(.*)\]\s*$", lines[i])
                            if mm:
                                vals = [v.strip().strip("'\"") for v in mm.group(2).split(",") if v.strip()]
                                job.matrix[mm.group(1)] = vals
                            i += 1
                        continue
                    i += 1
                continue
            if key == "matrix":
                i += 1
                while i < len(lines) and (not lines[i].strip() or indent_of(lines[i]) >= 8):
                    mm = re.match(r"^\s+([\w-]+):\s*\[(.*)\]\s*$", lines[i])
                    if mm:
                        vals = [v.strip().strip("'\"") for v in mm.group(2).split(",") if v.strip()]
                        job.matrix[mm.group(1)] = vals
                    i += 1
                continue
            if key == "env":
                i += 1
                while i < len(lines) and (not lines[i].strip() or indent_of(lines[i]) >= 8):
                    em = re.match(r"^\s+([A-Z][\w_]*):\s*(.*)$", lines[i])
                    if em:
                        job.job_env[em.group(1)] = em.group(2).strip().strip("'\"")
                    i += 1
                continue
            if key == "name":
                job.name = val
            elif key == "runs-on":
                job.runs_on = val
            elif key == "if":
                pass
            i += 1
            continue

        # ---- steps 条目：缩进 6，以 "- " 开头 ----
        if ind == 6 and stripped.startswith("- "):
            body = stripped[2:]
            step = Step(index=step_seq)
            step_seq += 1
            job.steps.append(step)
            first = re.match(r"^([\w-]+):\s*(.*)$", body)
            if first:
                key, val = first.group(1), first.group(2).strip()
                if key == "uses":
                    step.uses = val
                elif key == "name":
                    step.name = val
                elif key == "run":
                    if val in ("|", "|-", ">", ">-"):
                        content, i = parse_block_scalar(lines, i, 6)
                        step.run = content
                        continue
                    step.run = val
                elif key == "if":
                    step.if_expr = val
            i += 1
            continue

        # ---- step 级字段：缩进 8 ----
        if ind == 8 and step is not None:
            m = re.match(r"^([\w-]+):\s*(.*)$", stripped)
            if m:
                key, val = m.group(1), m.group(2).strip()
                if key == "uses":
                    step.uses = val
                elif key == "name":
                    step.name = val
                elif key == "if":
                    step.if_expr = val
                elif key == "run":
                    if val in ("|", "|-", ">", ">-"):
                        content, i = parse_block_scalar(lines, i, 8)
                        step.run = content
                        continue
                    step.run = val
                elif key in ("with", "env"):
                    block: dict[str, str] = step.with_block if key == "with" else step.env
                    i += 1
                    while i < len(lines) and (not lines[i].strip() or indent_of(lines[i]) >= 10):
                        km = re.match(r"^\s{10,}([\w-]+):\s*(.*)$", lines[i])
                        if km:
                            k, v = km.group(1), km.group(2).strip()
                            if v in ("|", "|-", ">", ">-"):
                                content, i = parse_block_scalar(lines, i, 10)
                                block[k] = content
                                continue
                            block[k] = v
                        i += 1
                    continue
            i += 1
            continue

        i += 1

    if not jobs:
        raise ValueError("没解析出任何 job —— ci.yml 格式超出了本脚本能处理的子集，必须人工核对")
    return jobs


# ---------------------------------------------------------------- 表达式

def expand_matrix(matrix: dict[str, list[str]]) -> list[dict[str, str]]:
    if not matrix:
        return [{}]
    combos: list[dict[str, str]] = [{}]
    for key, values in matrix.items():
        combos = [{**c, key: v} for c in combos for v in values]
    return combos


def substitute(expr: str | None, combo: dict[str, str]) -> str | None:
    if expr is None:
        return None

    def repl(m: re.Match) -> str:
        inner = m.group(1).strip()
        if inner.startswith("matrix."):
            key = inner[len("matrix."):]
            if key in combo:
                return combo[key]
            return m.group(0)
        return m.group(0)

    return re.sub(r"\$\{\{([^}]+)\}\}", repl, expr)


def eval_if(if_expr: str | None, event: str, job_failed: bool = False) -> bool:
    """极简 if 求值。只认得 always()/success()/failure()/cancelled() 与 github.event_name 的比较。

    认不得的表达式片段 → 按「执行」处理并显式打印 CN —— 这是刻意的：
    宁可多做一步并提醒，也不要因为看不懂就悄悄跳过一个步骤。
    """
    if not if_expr:
        return True
    expr = if_expr.strip()
    if expr.startswith("${{") and expr.endswith("}}"):
        expr = expr[3:-2].strip()

    for part in [p.strip() for p in expr.split("&&")]:
        if part == "always()":
            continue
        if part == "cancelled()":
            return False
        if part == "success()":
            if job_failed:
                return False
            continue
        if part == "failure()":
            if not job_failed:
                return False
            continue
        m = re.search(r"github\.event_name\s*(==|!=)\s*'([^']+)'", part)
        if m:
            op, want = m.group(1), m.group(2)
            ok = (event == want) if op == "==" else (event != want)
            if not ok:
                return False
            continue
        print(f"      {YEL}if 片段 [{part}] 无法求值，按「执行」处理（请人工核对）{RESET}")
    return True


# ---------------------------------------------------------------- 归档（upload-artifact 的本地等价实现）

def collect_files(pattern: str) -> list[Path]:
    pattern = pattern.strip()
    if not pattern:
        return []
    if not pattern.startswith("/"):
        base = REPO_ROOT / pattern
    else:
        base = Path(pattern)
    if "**" in pattern:
        hits = [Path(p) for p in globmod.glob(str(base), recursive=True)]
    else:
        hits = [Path(p) for p in globmod.glob(str(base))]
    return sorted({h for h in hits if h.is_file()})


def do_archive(step: Step, combo: dict[str, str], out_dir: Path, dry_run: bool, problems: list[str],
               records: list[dict]) -> None:
    name_raw = substitute(step.with_block.get("name"), combo) or f"artifact-{step.index}"
    path_raw = substitute(step.with_block.get("path"), combo) or ""
    retention = step.with_block.get("retention-days")
    if_no = step.with_block.get("if-no-files-found", "warn")

    patterns = [p.strip() for p in path_raw.splitlines() if p.strip()]
    if not patterns:
        problems.append(f"A4 归档 [{name_raw}] 没有配 path —— 会归档出一个空包")
        return

    matched: list[Path] = []
    for pat in patterns:
        matched.extend(collect_files(pat))

    print(f"   归档 -> {name_raw}")
    for pat in patterns:
        print(f"      pattern : {pat}")
    print(f"      if-no-files-found: {if_no}    retention-days: {retention or '<未设置>'}")

    if dry_run:
        print(f"      {RESET}[dry-run] 命中 {len(matched)} 个文件，未实际复制")
        return

    dest = out_dir / name_raw
    if dest.exists():
        shutil.rmtree(dest)
    total = 0
    for src in matched:
        rel = src.relative_to(REPO_ROOT) if str(src).startswith(str(REPO_ROOT)) else src
        target = dest / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, target)
        total += src.stat().st_size
    copied = sorted(dest.rglob("*")) if dest.exists() else []
    files = [p for p in copied if p.is_file()]

    print(f"      命中 {len(matched)} / 落盘 {len(files)} 个文件，共 {total:,} 字节")
    for p in files[:5]:
        print(f"        - {p.relative_to(dest)} ({p.stat().st_size:,} B)")
    if len(files) > 5:
        print(f"        ... 其余 {len(files) - 5} 个省略")

    records.append({
        "artifact": name_raw,
        "patterns": patterns,
        "matched": len(matched),
        "copied": len(files),
        "bytes": total,
        "if_no_files_found": if_no,
    })

    if not files and if_no == "error":
        problems.append(f"A4 归档 [{name_raw}] 零文件，而 if-no-files-found=error —— 这个 job 在 runner 上会红")
    elif not files:
        problems.append(f"A4 归档 [{name_raw}] 零文件（if-no-files-found={if_no}）—— 会静默归档空包")


# ---------------------------------------------------------------- 静态门禁

def lint_ci(jobs: dict[str, Job], check_path_exists: bool, out_dir: Path,
            allow_missing_build: bool, skip_codes: set[str] | None = None) -> tuple[list[str], list[str]]:
    fails: list[str] = []
    warns: list[str] = []

    for job in jobs.values():
        combos = expand_matrix(job.matrix)
        upload_steps = [s for s in job.steps if (s.uses or "").startswith("actions/upload-artifact")]
        if not upload_steps:
            fails.append(f"A1 job [{job.job_id}] 没有任何 upload-artifact 步骤 —— 失败现场不会被保留")

        for s in job.steps:
            uses = s.uses or ""
            if not uses:
                continue
            action = uses.split("@")[0]
            ref = uses.split("@")[1] if "@" in uses else ""
            if not re.fullmatch(r"v\d+(\.\d+)*", ref) and not re.fullmatch(r"[0-9a-f]{40}", ref):
                fails.append(f"S2 job [{job.job_id}] step [{s.label()}] 的 uses 未钉版本：{uses}")
            if re.match(r"^actions/upload-artifact@v[12]$", uses) or re.match(r"^actions/upload-artifact@v3$", uses):
                fails.append(f"A8 job [{job.job_id}] 使用了已废弃的 {uses}")
            if action not in KNOWN_ACTIONS:
                warns.append(f"未知 action {uses}（脚本不会在本地模拟它）")

        for s in upload_steps:
            if_expr = s.if_expr or ""
            if "always()" not in if_expr:
                fails.append(
                    f"A2 job [{job.job_id}] 归档 [{s.with_block.get('name', '?')}] 缺 if: always() —— "
                    f"跑挂的那一次将没有产物")
            retention = s.with_block.get("retention-days")
            if retention is None:
                fails.append(f"A3 job [{job.job_id}] 归档 [{s.with_block.get('name', '?')}] 未设 retention-days（缺省 90 天）")
            elif not retention.isdigit() or not (1 <= int(retention) <= 90):
                fails.append(f"A3 job [{job.job_id}] 归档 retention-days={retention} 不在 1..90")
            elif int(retention) > 7 and "target/*.jar" in (s.with_block.get("path") or ""):
                warns.append(f"A9 job [{job.job_id}] 归档含 fat jar 但 retention-days={retention}，配额成本高")
            if s.with_block.get("if-no-files-found") != "error":
                fails.append(
                    f"A4 job [{job.job_id}] 归档 [{s.with_block.get('name', '?')}] "
                    f"if-no-files-found 不是 error（缺省 warn = 路径写错也静默通过）")

            name_tpl = s.with_block.get("name") or ""
            if job.matrix and "${{" not in name_tpl:
                fails.append(
                    f"A5 job [{job.job_id}] 带 matrix 但 artifact name [{name_tpl}] 不含 matrix 变量 —— 各格会互相覆盖")

        # A5 同名冲突：同一 combo 下展开后的名字必须唯一
        for combo in combos:
            seen: dict[str, int] = {}
            for s in upload_steps:
                nm = substitute(s.with_block.get("name"), combo) or f"artifact-{s.index}"
                seen[nm] = seen.get(nm, 0) + 1
            dup = [k for k, v in seen.items() if v > 1]
            if dup:
                fails.append(f"A5 job [{job.job_id}] combo {combo} 下 artifact name 重复：{dup}")

        # A6 path 是否有命中
        if check_path_exists:
            for combo in combos:
                for s in upload_steps:
                    paths = substitute(s.with_block.get("path"), combo) or ""
                    for pat in [p.strip() for p in paths.splitlines() if p.strip()]:
                        if not collect_files(pat) and not allow_missing_build:
                            fails.append(f"A6 job [{job.job_id}] combo {combo} 的 path 零命中：{pat}")

        # A7 run 里引用的脚本必须存在
        for s in job.steps:
            if s.run:
                for m in re.finditer(r"scripts/[\w/]+(?:\.py|\.sh)", s.run):
                    p = REPO_ROOT / m.group(0)
                    if not p.is_file():
                        fails.append(f"A7 job [{job.job_id}] run 步骤引用了不存在的脚本：{m.group(0)}")

    if skip_codes:
        fails = [f for f in fails if f.split(" ", 1)[0] not in skip_codes]
        warns = [w for w in warns if w.split(" ", 1)[0] not in skip_codes]
    return fails, warns


# ---------------------------------------------------------------- 负向自检

def drop_steps_with(text: str, needle: str) -> str:
    """按**缩进块**删除包含某子串的 step。

    这里刻意不用正则：跨行非贪婪匹配会越过 step 边界（实测只删掉了每个 job 的
    第一个归档步骤，第二个原样留着），表现是「用例似乎不生效」，很难归因。
    """
    lines = text.splitlines()
    out: list[str] = []
    i = 0
    while i < len(lines):
        raw = lines[i]
        if indent_of(raw) == 6 and raw.strip().startswith("- "):
            block = [raw]
            j = i + 1
            while j < len(lines) and (not lines[j].strip() or indent_of(lines[j]) > 6):
                block.append(lines[j])
                j += 1
            if needle in "\n".join(block):
                i = j
                continue
            out.extend(block)
            i = j
            continue
        out.append(raw)
        i += 1
    return "\n".join(out)


# 每条门禁都必须证明「自己会红」。变异只作用于内存中的文本，不落盘、无副作用。
SELFCHECK_CASES: list[tuple[str, str, object, str, dict]] = [
    ("A2", "归档步骤去掉 always()", lambda t: t.replace(
        "if: always() && github.event_name != 'pull_request'",
        "if: github.event_name != 'pull_request'", 1), "A2", {}),
    ("A3", "删掉 retention-days", lambda t: re.sub(
        r"\n *retention-days: \d+", "", t, count=1), "A3", {}),
    # ⚠️ 变异必须带缩进锚，否则会打进注释里的同名字串（本文件注释就在讲这条规则），
    #    表现为「用例不生效」而不是「门禁失效」，非常容易误判为门禁有问题。
    ("A4", "if-no-files-found 放宽成 warn", lambda t: re.sub(
        r"\n(\s+)if-no-files-found: error", r"\n\1if-no-files-found: warn", t, count=1), "A4", {}),
    ("A5", "matrix job 的 artifact name 去掉 matrix 变量", lambda t: t.replace(
        "name: fatjar-jdk${{ matrix.jdk }}-sb${{ matrix.sb }}", "name: fatjar", 1), "A5", {}),
    ("A7", "run 步骤引用不存在的脚本", lambda t: t.replace(
        "python3 scripts/ops/count_tests.py --json", "python3 scripts/ops/does_not_exist.py --json", 1), "A7", {}),
    ("A8", "归档 action 降级到已废弃的 v3", lambda t: t.replace(
        "actions/upload-artifact@v4", "actions/upload-artifact@v3", 1), "A8", {}),
    # A1 的语义是「整个 job 一份归档都没有」，所以要删**全部**归档步骤才会触发。
    ("A1", "删掉全部归档步骤", lambda t: drop_steps_with(t, "actions/upload-artifact"), "A1", {}),

    # A6 依赖真实文件系统，所以只有它单独把 check_path_exists 打开。
    ("A6", "归档 path 指向不存在的路径", lambda t: re.sub(
        r"pivotos-admin-server/target/\*\.jar", "target/definitely-not-here/*.jar", t, count=1),
     "A6", {"check_path": True}),
]


def selfcheck(text: str) -> list[str]:
    """对 ci.yml 做「故意破坏 → 必须变红」验证。返回失败的自检项（空 = 全部合格）。"""
    broken: list[str] = []
    print("=" * 78)
    print("负向自检（每条门禁都必须证明自己会红）")
    print("=" * 78)
    for code, desc, mutate, expect, flags in SELFCHECK_CASES:
        try:
            mutated = mutate(text)  # type: ignore[operator]
            if mutated == text:
                broken.append(f"{code} 自检用例 {desc} 没生效（原文本未被改动）—— 用例本身失效，必须修")
                print(f"  {RED}FAIL{RESET} {code} {desc}：变异没生效（用例失效）")
                continue
            jobs = parse_ci(mutated)
            fails, _ = lint_ci(jobs, check_path_exists=flags.get("check_path", False),
                                out_dir=DEFAULT_OUT, allow_missing_build=False)
            hit = [f for f in fails if f.startswith(expect)]
            if hit:
                print(f"  {OK}OK  {RESET}{code} {desc} -> 如期变红：{hit[0][:88]}")
            else:
                broken.append(f"{code} 自检用例 {desc} 没有变红 —— 该门禁形同虚设")
                print(f"  {RED}FAIL{RESET} {code} {desc}：破坏后仍是绿，门禁无效")
        except Exception as exc:  # 解析崩也算漏：说明这个变异让脚本丧失了判断能力
            broken.append(f"{code} 自检用例 {desc} 触发异常 {exc}")
            print(f"  {RED}FAIL{RESET} {code} {desc}：异常 {exc}")
    return broken


# ---------------------------------------------------------------- simulate

def run_jobs(jobs: dict[str, Job], only: list[str], out_dir: Path, dry_run: bool,
             skip_maven: bool, event: str, allow_external: bool) -> tuple[list[str], list[dict]]:
    problems: list[str] = []
    records: list[dict] = []

    for job in jobs.values():
        if only and job.job_id not in only:
            continue
        print("=" * 78)
        print(f"job: {job.job_id}  ({job.name or ''})")
        if job.matrix:
            print(f"  matrix: {job.matrix}")
        if job.services:
            print(f"  {YEL}services: {job.services} —— 本地不提供容器{RESET}")
            if not allow_external:
                print(f"  {YEL}本 job 依赖 runner 容器数据源，本地执行的数据源将落到本机/开发环境；"
                      f"要真跑请加 --allow-external-datasource{RESET}")
        print("=" * 78)

        combos = expand_matrix(job.matrix)
        for combo in combos:
            if combo:
                print(f"  -- combo {combo}")
            job_failed = False
            for s in job.steps:
                if not eval_if(s.if_expr, event, job_failed):
                    print(f"    skip(if={s.if_expr}): {s.label()}")
                    continue

                uses = substitute(s.uses, combo)
                run_cmd = substitute(s.run, combo)

                if uses:
                    action = uses.split("@")[0]
                    if action == "actions/upload-artifact":
                        do_archive(s, combo, out_dir, dry_run, problems, records)
                    elif action == "actions/checkout":
                        print(f"    checkout -> 本地等价物就是当前工作区 {REPO_ROOT}")
                    elif action == "actions/setup-java":
                        want = substitute(s.with_block.get("java-version"), combo)
                        got = local_java_major()
                        mark = "OK" if (not want or got == want) else "WARN"
                        print(f"    setup-java want={want} local JDK={got}  {mark}"
                              f"{'' if (not want or got == want) else '（本地 JDK 与 CI 不一致，结论仅供参考）'}")
                    elif action == "actions/cache":
                        print(f"    cache -> 本地无等价物，跳过（不影响归档结论）")
                    else:
                        print(f"    {YEL}未知 action {action}，跳过{RESET}")
                    continue

                if run_cmd:
                    if skip_maven and run_cmd.lstrip().startswith("mvn"):
                        print(f"    skip-maven: {run_cmd.strip()[:90]}")
                        continue
                    print(f"    run: {run_cmd.strip()}")
                    if dry_run:
                        print(f"      {DIM}[dry-run] 未执行{RESET}")
                        continue
                    env = dict(os.environ)
                    env.update(job.job_env)
                    env.update({k: substitute(v, combo) or "" for k, v in s.env.items()})
                    proc = subprocess.run(run_cmd, shell=True, cwd=str(REPO_ROOT), env=env)
                    if proc.returncode != 0:
                        print(f"    {RED}命令退出码 {proc.returncode}{RESET}")
                        if "always()" not in (s.if_expr or ""):
                            job_failed = True
                            problems.append(
                                f"SIMULATE job [{job.job_id}] combo {combo} 步骤 [{s.label()}] 失败，退出码 {proc.returncode}")
                            break
            if job_failed:
                print(f"  {RED}job {job.job_id} combo {combo} 判定失败{RESET}")
    return problems, records


def resolve_mvn() -> tuple[Path | None, list[Path]]:
    """定位本机 Maven。返回 (选定的 mvn, 存在歧义的候选列表)。

    ⚠️ 两个坑都踩过，别再踩：
      ① 不能假定 `mvn` 在 PATH 上 —— 本机 Maven 装在 `~/Documents/File/Plugin/apache-maven-*/bin`
         且从未加进 PATH（按项目口径还必须是 3.9.16 那一套、且不加 -s），直接跑会 127。
      ② 候选有多个时**不许猜**。早期版本用 glob sorted() 取第一个，拿到的是 **3.6.3**，
         用它照样能打包成功 —— 版本号不符却毫无信号，属于最坏的一类错误。
         正确做法是报歧义 + 要求 export PIVOTOS_MVN。
    """
    preferred = os.environ.get("PIVOTOS_MVN")
    if preferred and Path(preferred).is_file():
        return Path(preferred), []

    found: list[Path] = []
    which = shutil.which("mvn")
    if which:
        found.append(Path(which))
    found.extend(sorted((Path.home() / "Documents/File/Plugin").glob("apache-maven-*/bin/mvn")))
    found.extend([Path(p) for p in ("/opt/homebrew/opt/maven/bin/mvn",)])

    uniq = sorted({p.resolve() for p in found if p.is_file()})
    if len(uniq) == 1:
        return uniq[0], []
    return None, uniq


def local_java_major() -> str:
    try:
        proc = subprocess.run("java -version 2>&1 | head -1", shell=True, capture_output=True, text=True)
        m = re.search(r'"?(\d+)', proc.stdout)
        return m.group(1) if m else "?"
    except Exception:  # pragma: no cover
        return "?"


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description="CI 同构本地自证 + 产物归档门禁")
    ap.add_argument("--lint-only", action="store_true", help="只做静态门禁")
    ap.add_argument("--simulate", action="store_true", help="真跑 run 步骤并落地归档")
    ap.add_argument("--job", action="append", default=[], help="只处理指定 job，可重复")
    ap.add_argument("--skip-maven", action="store_true", help="跳过 mvn 开头的 run 步骤（已构建过）")
    ap.add_argument("--dry-run", action="store_true", help="只打印将要做的事")
    ap.add_argument("--allow-external-datasource", action="store_true",
                    help="承认 test job 的 MySQL/Redis 会落到本机/开发环境，真跑")
    ap.add_argument("--event", default="push", choices=["push", "pull_request"], help="模拟 github.event_name")
    ap.add_argument("--allow-missing-build", action="store_true",
                    help="A6 零命中只警告不失败（工作区还没构建过时用）")
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT, help="归档落盘位置")
    ap.add_argument("--selfcheck", action="store_true", help="负向自检：故意破坏 ci.yml，验证门禁会红")
    ap.add_argument("--json", type=Path, default=None, help="冻结产物")
    args = ap.parse_args()

    if not args.lint_only and not args.simulate:
        args.lint_only = True

    if not CI_YML.is_file():
        print(f"FAIL 找不到 {CI_YML}")
        return 2

    text = CI_YML.read_text(encoding="utf-8")
    try:
        jobs = parse_ci(text)
    except ValueError as exc:
        print(f"FAIL 解析 ci.yml 失败：{exc}")
        return 2

    print("=" * 78)
    print(f"ci.yml 解析结果：{len(jobs)} 个 job -> {list(jobs)}")
    for job in jobs.values():
        print(f"  - {job.job_id}: {len(job.steps)} steps"
              + (f", matrix={job.matrix}" if job.matrix else "")
              + (f", services={job.services}" if job.services else "")
              + (f", upload-artifact x{sum(1 for s in job.steps if (s.uses or '').startswith('actions/upload-artifact'))}"
                 if any((s.uses or "").startswith("actions/upload-artifact") for s in job.steps) else ", 无归档"))
    print("=" * 78)

    all_problems: list[str] = []
    records: list[dict] = []

    # ---- Maven 必须在 PATH 上：CI 里有 setup-java + runner 预装，本地不一定有 ----
    mvn, candidates = resolve_mvn()
    if mvn is None and args.simulate and not args.dry_run:
        print("FAIL 本机 Maven 不明确 —— 不许猜（项目口径是 apache-maven-3.9.16，用错版本也能打包成功）")
        if candidates:
            print("  发现多个候选，请显式指定其一：")
            for c in candidates:
                print(f"    - {c}")
        else:
            print("  完全没有找到 mvn")
        print("  修法：export PIVOTOS_MVN=/path/to/apache-maven-3.9.16/bin/mvn")
        return 2
    if mvn is not None:
        os.environ["PATH"] = f"{mvn.parent}{os.pathsep}{os.environ.get('PATH', '')}"
        print(f"Maven：{mvn}")

    # ---- S1 由解析阶段保证（解析不出 job 已 return 2） ----
    # A6 检查「归档 path 能不能命中文件」，而产物是 run 步骤跑出来的 ——
    # 所以 simulate 时它必须放到 run **之后**做，否则必然误报。
    #
    # ⚠️ 同一个道理反过来也成立（V3.1-J 遗留④，本批修）：**lint-only 不跑 run**，
    #    产物目录（target/surefire-reports、target/ci-*.json）根本不存在，
    #    此时查 A6 必然假红 —— 实测 `--lint-only` 稳定 FAIL 6 项，掩盖了真正的结构问题。
    #    故 A6 定为**运行态门禁**：只有 simulate 且 run 之后才查，lint-only 一律跳过并显式说明。
    pre_run = args.simulate and not args.dry_run
    is_lint_only = not args.simulate or args.dry_run
    fails, warns = lint_ci(
        jobs,
        check_path_exists=bool(pre_run and not args.allow_missing_build),
        out_dir=args.out_dir,
        allow_missing_build=args.allow_missing_build,
        skip_codes={"A6"} if (pre_run or is_lint_only) else None,
    )
    if is_lint_only:
        print(f"{YEL}说明{RESET}：A6（归档 path 产物层）已跳过 —— 它是运行态门禁，"
              f"lint-only 不跑 run 就没有产物；要查请用 --simulate")
    print(f"\n静态门禁（结构层）：FAIL {len(fails)} / WARN {len(warns)}")
    for w in warns:
        print(f"  {YEL}WARN{RESET} {w}")
    for f in fails:
        print(f"  {RED}FAIL{RESET} {f}")
    all_problems.extend(fails)

    # ---- 负向自检：门禁必须先证明自己会红，否则「绿」毫无意义 ----
    if args.selfcheck:
        if fails:
            all_problems.append("负向自检被跳过：静态门禁自身尚有 FAIL，先把门禁修绿")
            print("\n静态门禁未通过，跳过负向自检")
        else:
            all_problems.extend(selfcheck(text))

    if args.simulate:
        probs, recs = run_jobs(jobs, args.job, args.out_dir, args.dry_run,
                               args.skip_maven, args.event, args.allow_external_datasource)
        all_problems.extend(probs)
        records.extend(recs)

        if pre_run and not args.allow_missing_build:
            # A6：产物应当在 run 之后存在了。这一步查就是「归档能不能真的收齐」。
            # 只对本轮**真的执行过**的 job 查产物层——没跑过的 job 当然没有报告。
            executed = {k: v for k, v in jobs.items() if (not args.job or k in args.job)}
            a6_fails, _ = lint_ci(
                executed, check_path_exists=True, out_dir=args.out_dir, allow_missing_build=False,
                skip_codes={"S2", "A1", "A2", "A3", "A4", "A5", "A7", "A8", "A9"},
            )
            print(f"\n静态门禁（A6 产物层）：FAIL {len(a6_fails)}")
            for f in a6_fails:
                print(f"  {RED}FAIL{RESET} {f}")
            all_problems.extend(a6_fails)

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps({
            "ci_yml": str(CI_YML),
            "jobs": list(jobs),
            "problems": all_problems,
            "artifacts": records,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n已写出：{args.json}")

    print("\n" + "=" * 78)
    if all_problems:
        print(f"{RED}FAIL{RESET} 共 {len(all_problems)} 项：")
        for p in all_problems:
            print(f"  - {p}")
        return 1
    what = "静态门禁" if args.lint_only else "同构自证"
    print(f"{OK}PASS{RESET} {what}通过"
          + (f"；归档 {len(records)} 份，落盘 {args.out_dir}" if records else ""))
    if args.simulate:
        print(f"{YEL}注意{RESET}：本脚本证明的是 steps 顺序 / 命令 / 归档路径与内容自洽，"
              f"不证明 runner 侧的 services、cache、权限行为")
    return 0


if __name__ == "__main__":
    sys.exit(main())
