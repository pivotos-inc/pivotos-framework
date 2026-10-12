#!/usr/bin/env python3
"""统一 IT 计数脚本（v3.1.0 P1-B1 清偿项，V3.1-H 遗留第 1 项）。

背景：本项目历来「每批用一条临时命令算 IT 总数」，口径自然会漂——
V3.1-H 开工时发现同一份代码用不同命令能差出 10 条，且来历查不明。
根治办法就是把口径冻结进脚本：**数字只能跟本脚本跑出来的前后对比，
不能跟任何历史记忆里的数字比。**

口径（本脚本即定义）：
  * 数据源 = 各模块 ``target/surefire-reports/TEST-*.xml`` 的 ``<testsuite>`` 属性
    （结构化字段，比解析 ``*.txt`` 的 "Tests run: x, Failures: y" 文本更稳）。
  * 只统计 ``src/test`` 下的模块报告，不含 ``data/migration/**`` 迁移样例工程。
  * 不做任何「按段」切分：段一 / 段二 是历史上手动跑出来的产物，
    脚本只给**全量**与**按模块明细**，谁要分段就自己按模块相加。

用法：
    python3 scripts/ops/count_tests.py                 # 统计并打印
    python3 scripts/ops/count_tests.py --clean         # 先删历史报告再统计（须在 IT 之前跑）
    python3 scripts/ops/count_tests.py --baseline X    # 与基线比对，不一致则以退出码 2 失败
    python3 scripts/ops/count_tests.py --json out.json # 冻结产物

已知前提（脚本会自检并报错，不静默）：
  * 报告目录不存在 → 直接 FAIL（「零命中」必须响，否则会被当成 0 条通过）。
  * 报告时间戳早于阈值 → 警告「可能是历史残留」。
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# 需要排除的目录：迁移样例工程不参与构建，其报告（若有）不计入
EXCLUDE_DIR_PARTS = (
    "/data/migration/",
    "/spike/",
    "/node_modules/",
)

REPORT_GLOB = "TEST-*.xml"


def iter_report_files(root: Path):
    """遍历全部 surefire XML 报告（排除迁移样例 / spike）。"""
    for path in root.rglob(REPORT_GLOB):
        posix = path.as_posix()
        if "/target/surefire-reports/" not in posix:
            continue
        if any(part in posix for part in EXCLUDE_DIR_PARTS):
            continue
        yield path


def module_of(report: Path) -> str:
    """从报告路径反推模块名：.../<module>/target/surefire-reports/TEST-*.xml"""
    parts = report.as_posix().split("/target/surefire-reports/")[0].split("/")
    return parts[-1] if parts else "?"


def parse_report(report: Path):
    """解析单个报告的 tests/failures/errors/skipped 与所有用例耗时。"""
    try:
        tree = ET.parse(report)
    except ET.ParseError as exc:
        return None, f"{report}: XML 解析失败（{exc}）"
    root = tree.getroot()
    # surefire 报告根元素是 <testsuite>，failsafe 会是 <testsuite> 包在 <testsuites> 里
    suite = root if root.tag == "testsuite" else root.find("testsuite")
    if suite is None:
        return None, f"{report}: 找不到 testsuite 元素"

    def num(attr: str) -> int:
        try:
            return int(suite.get(attr, "0") or 0)
        except ValueError:
            return 0

    return {
        "tests": num("tests"),
        "failures": num("failures"),
        "errors": num("errors"),
        "skipped": num("skipped"),
        "time": float(suite.get("time", "0") or 0),
    }, None


def clean_reports(root: Path) -> int:
    """删除历史报告：残留报告会把计数顶高（曾把 819 顶成 823）。"""
    removed = 0
    for d in root.rglob("surefire-reports"):
        posix = d.as_posix()
        if "/target/surefire-reports" not in posix:
            continue
        if any(part in posix for part in EXCLUDE_DIR_PARTS):
            continue
        shutil.rmtree(d, ignore_errors=True)
        removed += 1
    return removed


def main() -> int:
    ap = argparse.ArgumentParser(description="PivotOS 统一 IT 计数（口径冻结）")
    ap.add_argument("--clean", action="store_true", help="统计前先删除历史 surefire 报告")
    ap.add_argument("--clean-only", action="store_true",
                    help="只删历史报告然后退出（跑 IT 之前先做这一步）")
    ap.add_argument("--baseline", type=int, default=None, help="与基线总数比对，不一致则退出码 2")
    ap.add_argument("--json", type=str, default=None, help="把结果冻结成 JSON 产物")
    ap.add_argument("--max-age-hours", type=float, default=24.0,
                    help="报告新鲜度阈值（超过则警告可能是历史残留，默认 24h）")
    args = ap.parse_args()

    if args.clean or args.clean_only:
        n = clean_reports(REPO_ROOT)
        print(f"[count-tests] 已清理历史报告目录 {n} 个")
        if args.clean_only:
            print("[count-tests] clean-only：跑完 IT 后再不带参数执行本脚本统计")
            return 0

    reports = sorted(iter_report_files(REPO_ROOT))
    if not reports:
        print("[count-tests] FAIL 一个报告都没找到——要么还没跑 IT，要么路径口径变了；"
              "「零命中」绝不能当 0 条通过", file=sys.stderr)
        return 1

    problems = []
    per_module: dict[str, dict] = {}
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0}

    for report in reports:
        stats, err = parse_report(report)
        if err:
            problems.append(err)
            continue
        mod = module_of(report)
        agg = per_module.setdefault(mod, {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0})
        for key in ("tests", "failures", "errors", "skipped"):
            agg[key] += stats[key]
            total[key] += stats[key]
        agg["time"] += stats["time"]
        total["time"] += stats["time"]

        age_h = (time.time() - report.stat().st_mtime) / 3600.0
        if age_h > args.max_age_hours:
            problems.append(f"{report}: 报告已存在 {age_h:.1f}h（可能是历史残留，会顶高计数）")

    print("=" * 72)
    print("[count-tests] PivotOS IT 计数（口径已冻结：只与本脚本的前后结果对比）")
    print("=" * 72)
    print(f"{'模块':<44}{'用例':>7}{'失败':>6}{'错误':>6}{'跳过':>6}{'耗时s':>9}")
    print("-" * 72)
    for mod in sorted(per_module):
        s = per_module[mod]
        print(f"{mod:<44}{s['tests']:>7}{s['failures']:>6}{s['errors']:>6}{s['skipped']:>6}{s['time']:>9.1f}")
    print("-" * 72)
    print(f"{'合计':<44}{total['tests']:>7}{total['failures']:>6}{total['errors']:>6}"
          f"{total['skipped']:>6}{total['time']:>9.1f}")
    print("=" * 72)
    print(f"[count-tests] 模块数：{len(per_module)}　报告数：{len(reports)}")

    if problems:
        print(f"[count-tests] 警告 {len(problems)} 条：", file=sys.stderr)
        for p in problems[:20]:
            print(f"  - {p}", file=sys.stderr)

    if args.json:
        payload = {
            "generatedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
            "total": total,
            "modules": {k: v for k, v in sorted(per_module.items())},
            "reportCount": len(reports),
            "problems": problems,
        }
        # 目录不存在会 FileNotFoundError：本机根模块往往没有 target/（只有子模块才有），
        # 而 CI 里 target/ 是存在的，于是这个坑只在本机复现、CI 上看不到（2026-10-12）。
        out = Path(args.json)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[count-tests] 已冻结到 {args.json}")

    bad = total["failures"] + total["errors"]
    if bad:
        print(f"[count-tests] FAIL 存在失败/错误共 {bad} 条", file=sys.stderr)
        return 1

    if args.baseline is not None:
        if total["tests"] != args.baseline:
            print(f"[count-tests] 与基线不符：基线 {args.baseline}，实测 {total['tests']}"
                  f"（差值 {total['tests'] - args.baseline:+d}）", file=sys.stderr)
            return 2
        print(f"[count-tests] 与基线一致：{total['tests']}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
