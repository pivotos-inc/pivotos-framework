#!/usr/bin/env python3
"""阶段7 迁移步骤执行 + 评审门禁 E2E 测试（S115 fixture 复壮版）。

流程（全部在自建任务上完成，跑完自清）：
    自建任务 → 解析 → AI 分析 → AI 计划（PLANNED）
    → 执行步骤1 → 自测通过 → 评审 PASS → 已完成（completedSteps=1）
    → 执行步骤2 → 评审 REJECT → 已驳回 → 重新执行 → 自测通过

改动背景（S114 §5.1 已定性）：
    原脚本硬编码 ``TASK_ID = "2089577593306185730"``，该行在 dev 库已零命中 → data=null → KeyError。
复壮方案（① 脚本自建任务）：源码 fixture 由 migration_fixture.SOURCE_FILES 即时合成，
    计划步骤由 AI 现场生成（要求 ≥2 步，不足则重跑一次 plan），不依赖任何库内遗留数据。
    LLM 通道不可得时改判 skip（exit=0）。
"""
import sys

from migration_fixture import (
    STATUS, STEP_STATUS, FixtureError, FixtureUnavailable, artifact_detail,
    artifact_list, bootstrap, cleanup, detail, ensure_ai_key, execute_step, log,
    login, plan, release_ai_key, review_step, run_name, step_list,
)


def _step(hdr, task_id, seq):
    """按序号取步骤（第 n 条，seq 从 1 开始）。"""
    steps = step_list(hdr, task_id)
    if len(steps) < seq:
        raise FixtureError(f"步骤数不足：期望 ≥{seq}，实际 {len(steps)}")
    return steps[seq - 1], steps


def main():
    hdr = login()
    key_id, own = ensure_ai_key(hdr)
    if key_id is None:
        log("SKIP", "dev 环境无可用 LLM 通道且取不到静态 Key —— plan/execute 阶段不可得，按口径改判 skip")
        return 0

    task_id = None
    try:
        # ── Step 1: 自建任务到 PLANNED(6) ───────────────────────
        log("STEP1", "自建迁移任务并推进到 PLANNED(6) ...")
        task_id, task = bootstrap(hdr, run_name("S115-execute"), upto="plan")
        log("STEP1", f"taskId={task_id} status={STATUS.get(task['status'])} "
                     f"totalSteps={task.get('totalSteps')}  OK")

        steps = step_list(hdr, task_id)
        if len(steps) < 2:
            # AI 偶发只给出 1 步计划：重跑一次 plan（plan 内部会先清旧步骤，幂等）
            log("STEP1", f"计划步骤数={len(steps)} < 2，重跑一次 plan ...")
            plan(hdr, task_id)
        step1, steps = _step(hdr, task_id, 1)
        step2, steps = _step(hdr, task_id, 2)
        log("STEP1", f"步骤1: {step1['name']}  步骤2: {step2['name']}  totalSteps={len(steps)}  OK")

        # ── Step 2: 执行步骤1 → SELF_TEST_PASSED(2) ─────────────
        log("STEP2", f"执行步骤1 (id={step1['id']}) ...")
        s1 = execute_step(hdr, task_id, step1["id"])
        log("STEP2", f"步骤1 状态={STEP_STATUS.get(s1['status'])}")
        if s1["status"] != 2:
            raise FixtureError(f"步骤1 未进入自测通过，当前状态={s1['status']} errorMsg={s1.get('errorMsg')}")

        # ── Step 3: 验证产物 ───────────────────────────────────
        log("STEP3", "验证步骤1产物 ...")
        artifacts = artifact_list(hdr, step1["id"])
        if not artifacts:
            raise FixtureError("步骤1 产物数为 0")
        log("STEP3", f"产物数={len(artifacts)}  OK")
        for a in artifacts[:3]:
            log("STEP3", f"  [{a['artifactType']}] {a['relativePath']}")

        content = (artifact_detail(hdr, artifacts[0]["id"]).get("generatedContent") or "")
        if len(content) < 10:
            raise FixtureError(f"产物内容为空或过短：{len(content)}")
        log("STEP3", f"产物详情内容长度={len(content)}  OK")

        # ── Step 4: 评审 PASS 步骤1 → COMPLETED(6) ──────────────
        log("STEP4", "评审通过步骤1 ...")
        review_step(hdr, step1["id"], "PASS", "S115 E2E 自动评审通过")
        s1, _ = _step(hdr, task_id, 1)
        task = detail(hdr, task_id)
        log("STEP4", f"步骤1 状态={STEP_STATUS.get(s1['status'])}")
        if s1["status"] != 6:
            raise FixtureError(f"步骤1 应为已完成(6)，实际={s1['status']}")
        if task.get("completedSteps") != 1:
            raise FixtureError(f"completedSteps 应为 1，实际={task.get('completedSteps')}")
        log("STEP4", f"步骤1 已完成(6)，completedSteps=1，任务状态={STATUS.get(task['status'])}  OK")

        # ── Step 5: 执行步骤2 → 评审 REJECT → 已驳回(5) ─────────
        log("STEP5", f"执行步骤2 (id={step2['id']}) ...")
        s2 = execute_step(hdr, task_id, step2["id"])
        if s2["status"] != 2:
            raise FixtureError(f"步骤2 未进入自测通过，当前={STEP_STATUS.get(s2['status'])}")
        log("STEP5", "步骤2 自测通过  OK")

        review_step(hdr, step2["id"], "REJECT", "S115 E2E 驳回测试")
        s2, _ = _step(hdr, task_id, 2)
        if s2["status"] != 5:
            raise FixtureError(f"步骤2 应为已驳回(5)，实际={s2['status']}")
        log("STEP5", f"步骤2 已驳回(5)，reviewComment={s2.get('reviewComment')}  OK")

        # ── Step 6: 驳回后重新执行 ──────────────────────────────
        log("STEP6", "重新执行步骤2（验证 REJECTED 可重执行）...")
        s2 = execute_step(hdr, task_id, step2["id"])
        if s2["status"] != 2:
            raise FixtureError(f"重新执行后步骤2 应为自测通过(2)，实际={s2['status']}")
        log("STEP6", "重新执行成功，步骤2 再次自测通过  OK")

        print()
        print("=" * 60)
        print("  阶段7 步骤执行 + 评审门禁 E2E 测试：全部通过 ✓")
        print(f"  任务ID（自建） : {task_id}")
        print(f"  任务状态       : {STATUS.get(task['status'], task['status'])}")
        print(f"  completedSteps : {task.get('completedSteps')} / {task.get('totalSteps')}")
        print(f"  步骤1产物数    : {len(artifacts)}")
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
