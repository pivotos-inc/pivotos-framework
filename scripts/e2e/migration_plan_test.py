#!/usr/bin/env python3
"""阶段6 迁移计划生成 E2E 测试（S115 fixture 复壮版）。

改动背景（S114 §5.1 已定性）：
    原脚本硬编码 ``TASK_ID = "2089577593306185730"``（注释「自测任务，已上传 7132 文件」）。
    该行在 dev 库（pivotos_dev）已**零命中**——现仅存 1 行 id=2103521132021088258 / status=0，
    任务详情返回 data=null → 脚本在 ``task['status']`` 处 KeyError（fixture 漂移，非代码回归）。

复壮方案（选优先级最高的①「脚本自建任务」）：
    建任务 → 上传合成源码包 → 解析 → AI 分析到 ANALYZED(4) → AI 计划 → 轮询 PLANNED(6)
    → 断言（步骤数 > 0 / migrationPlan 非空 / totalSteps > 0）→ 自清（DB 七表 + 工作目录）。
    源码 fixture 由 migration_fixture.SOURCE_FILES 即时合成，不依赖任何库内遗留行，
    因此不可能再次发生「DB 行丢失」型漂移。LLM 通道不可得时按 ③ 口径改判 skip（exit=0）。
"""
import sys

from migration_fixture import (
    STATUS, FixtureError, FixtureUnavailable, bootstrap, cleanup,
    ensure_ai_key, log, login, plan, release_ai_key, run_name, step_list,
)


def main():
    hdr = login()
    key_id, own = ensure_ai_key(hdr)
    if key_id is None:
        log("SKIP", "dev 环境无可用 LLM 通道且取不到静态 Key —— plan 阶段不可得，按口径改判 skip")
        return 0

    task_id = None
    try:
        # ── Step 1: 自建任务到 ANALYZED(4) ───────────────────────
        log("STEP1", "自建迁移任务并推进到 ANALYZED(4) ...")
        task_id, task = bootstrap(hdr, run_name("S115-plan"), upto="analyze")
        s = task["status"]
        log("STEP1", f"status={STATUS.get(s, s)}  name={task['name']}  OK")
        if s != 4:
            raise FixtureError(f"任务状态不是 ANALYZED(4)，当前={s}，无法执行计划生成测试")

        # ── Step 2: 触发 AI 迁移计划生成 ─────────────────────────
        log("STEP2", "POST /migration/task/plan ...")
        task = plan(hdr, task_id)

        # ── Step 3: 验证 migration_step 记录数 > 0 ───────────────
        log("STEP3", "GET /migration/step/list ...")
        steps = step_list(hdr, task_id)
        if not steps:
            raise FixtureError("migration_step 记录数为 0，计划生成异常")
        log("STEP3", f"步骤数={len(steps)}  OK")
        for step in steps[:5]:
            log("STEP3", f"  #{step.get('stepNo')} [{step.get('stepType')}] {step.get('name')}")

        # ── Step 4: 验证 migrationPlan 非空 ──────────────────────
        log("STEP4", "验证 migrationPlan 字段 ...")
        plan_text = task.get("migrationPlan") or ""
        if len(plan_text) < 10:
            raise FixtureError(f"migrationPlan 为空或过短: '{plan_text[:100]}'")
        log("STEP4", f"migrationPlan 长度={len(plan_text)}  totalSteps={task.get('totalSteps', 0)}  OK")
        if not task.get("totalSteps"):
            raise FixtureError("totalSteps 为 0，计划生成未落库")

        print()
        print("=" * 60)
        print("  阶段6 迁移计划生成 E2E 测试：全部通过 ✓")
        print(f"  任务ID（自建） : {task_id}")
        print(f"  最终状态       : {STATUS.get(task['status'], task['status'])}")
        print(f"  迁移步骤数     : {task.get('totalSteps', 0)}")
        print(f"  migrationPlan  : {len(plan_text)} 字符")
        print(f"  前3步骤        :")
        for step in steps[:3]:
            print(f"    #{step.get('stepNo')} [{step.get('stepType')}] {step.get('name')}")
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
