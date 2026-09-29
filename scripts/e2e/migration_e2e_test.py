#!/usr/bin/env python3
"""PivotOS 系统迁移功能全链路 E2E 自测脚本（S115 fixture 复壮版）。

链路：自建任务 → 上传合成源码包 → 解析 → AI 架构分析 → AI 迁移计划 → 断言 → 自清。

改动背景（S114 §5.1 已定性）：
    原脚本硬编码 ``TASK_ID = "2089577593306185730"``（注释「自测任务，已上传 7132 文件」），
    该行在 dev 库已零命中 → 任务详情 data=null → KeyError('data')。fixture 漂移 ≠ 代码回归。
复壮方案（① 脚本自建任务）：fixture 由 migration_fixture.SOURCE_FILES 即时合成，
    不依赖库内遗留行，跑完自清（DB 七表 + 工作目录）。LLM 通道不可得时改判 skip（exit=0）。
"""
import sys

from migration_fixture import (
    STATUS, FixtureError, FixtureUnavailable, analyze, bootstrap, cleanup, detail,
    ensure_ai_key, log, login, plan, release_ai_key, run_name, step_list,
)


def main():
    hdr = login()
    key_id, own = ensure_ai_key(hdr)
    if key_id is None:
        log("SKIP", "dev 环境无可用 LLM 通道且取不到静态 Key —— analyze/plan 阶段不可得，按口径改判 skip")
        return 0

    task_id = None
    try:
        # ── Step 1: 自建任务并解析到 ANALYZED(4) ─────────────────
        log("STEP1", "自建迁移任务 → 上传合成源码包 → 解析 ...")
        task_id, task = bootstrap(hdr, run_name("S115-e2e"), upto="parse")
        log("STEP1", f"taskId={task_id} name={task['name']} status={STATUS.get(task['status'])}  OK")

        # ── Step 2: AI 架构分析 ─────────────────────────────────
        log("STEP2", "触发 AI 架构分析 POST /migration/task/analyze ...")
        analyze(hdr, task_id)
        task = detail(hdr, task_id)
        report = task.get("analysisReport") or ""
        if len(report) < 50:
            raise FixtureError(f"analysisReport 为空或过短（{len(report)} 字符）：'{report[:100]}'")
        log("STEP2", f"analysisReport 长度={len(report)}  前80字符：{report[:80]}  OK")

        # ── Step 3: AI 迁移计划生成 → 轮询 PLANNED(6) ───────────
        log("STEP3", "触发 AI 迁移计划生成 POST /migration/task/plan ...")
        task = plan(hdr, task_id)

        # ── Step 4: 验证 migration_step 记录 ────────────────────
        steps = step_list(hdr, task_id)
        if not steps:
            raise FixtureError("migration_step 记录数为 0，计划生成异常")
        log("STEP4", f"migration_step 记录数={len(steps)}  OK")
        for step in steps[:3]:
            log("STEP4", f"  stepNo={step.get('stepNo')} type={step.get('stepType')} name={step.get('name')}")

        # ── Step 5: 验证 migrationPlan 写入 ─────────────────────
        plan_text = task.get("migrationPlan") or ""
        if len(plan_text) < 10:
            raise FixtureError(f"migrationPlan 为空或过短：'{plan_text[:100]}'")
        log("STEP5", f"migrationPlan 长度={len(plan_text)}  总步骤数={task.get('totalSteps', 0)}  OK")

        print()
        print("=" * 60)
        print("  迁移全链路 E2E 测试结果：全部通过 √")
        print(f"  任务ID（自建） : {task_id}")
        print(f"  最终状态       : {STATUS.get(task['status'], task['status'])}")
        print(f"  分析报告长     : {len(report)} 字符")
        print(f"  迁移计划长     : {len(plan_text)} 字符")
        print(f"  迁移步骤数     : {task.get('totalSteps', 0)}")
        print("=" * 60)
        return 0
    finally:
        if task_id:
            cleanup(task_id)
        release_ai_key(hdr, key_id, own)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except FixtureUnavailable as e:
        log("SKIP", str(e))
        sys.exit(0)
    except FixtureError as e:
        log("FAIL", str(e))
        sys.exit(1)
