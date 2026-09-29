#!/usr/bin/env python3
"""阶段8 产物落盘、撤销、路径穿越防护与状态机校验 E2E（S115 fixture 复壮版）。

流程（全部在自建任务上完成，跑完自清）：
    1. 自建任务 → 解析 → AI 分析 → AI 计划（PLANNED）
    2. 执行步骤1 → 评审 PASS → COMPLETED（任务进入 EXECUTING）
    3. 执行步骤2 → 评审 PASS → COMPLETED
    4. 批量落盘步骤2产物 → 验证文件真实存在且 applied=true
    5. 撤销单个产物 → 验证文件删除、applied=false
    6. 重新落盘单个产物 → 验证文件恢复
    7. 路径穿越防护：直连 DB 插入 relativePath 含 ../ 的产物，apply 应被拒绝
    8. 对照组：另建一个 CREATED(0) 态任务，complete / rollback 均应被拒（状态白名单校验）

改动背景（S114 §5.1 已定性）：
    ① 原脚本硬编码 ``TASK_ID = "2089577593306185730"``，dev 库零命中 → data=null → TypeError。
    ② 原「他任务」对照依赖分页里恰好存在一条 status ∉ (7,8) 的任务，dev 库当时只剩 1 行
       id=2103521132021088258/status=0，**命中与否全看运气**，且随迁移任务表清空必然失效。
    本版改为**自建对照组**（一个 CREATED 态任务），跑完一并自清，不再依赖存量数据。

同时修正：原脚本 DB 连接指向 ``database='pivotos'``（Windows 时代遗留，本机 dev 库是 pivotos_dev），
落盘根目录 TARGET_ROOT 现由 migration_fixture.WORKSPACE 统一推导（同 application.yml 口径）。
"""
import os
import sys

import requests

from migration_fixture import (
    BASE, STATUS, STEP_STATUS, FixtureError, FixtureUnavailable, WORKSPACE,
    artifact_list, bootstrap, check, cleanup, connect_db, create_task, detail,
    ensure_ai_key, execute_step, log, login, plan, release_ai_key, review_step,
    run_name, step_list,
)


def apply_artifact(hdr, artifact_id, expect_fail=False):
    r = requests.post(f"{BASE}/migration/artifact/apply",
                      params={"artifactId": artifact_id}, headers=hdr, timeout=60)
    return check(r, f"产物落盘 art={artifact_id}", expect_fail=expect_fail)


def main():
    hdr = login()
    key_id, own = ensure_ai_key(hdr)
    if key_id is None:
        log("SKIP", "dev 环境无可用 LLM 通道且取不到静态 Key —— plan/execute 阶段不可得，按口径改判 skip")
        return 0

    task_id = None
    ctrl_id = None
    try:
        target_root = os.path.join(WORKSPACE, "{task_id}", "target")

        # ── Step 1: 自建主任务到 PLANNED(6) ─────────────────────
        log("STEP1", "自建迁移任务并推进到 PLANNED(6) ...")
        task_id, task = bootstrap(hdr, run_name("S115-apply"), upto="plan")
        steps = step_list(hdr, task_id)
        if len(steps) < 2:
            log("STEP1", f"计划步骤数={len(steps)} < 2，重跑一次 plan ...")
            plan(hdr, task_id)
            steps = step_list(hdr, task_id)
        if len(steps) < 2:
            raise FixtureError(f"步骤数不足：{len(steps)}")
        step1, step2 = steps[0], steps[1]
        log("STEP1", f"taskId={task_id} status={STATUS.get(task['status'])} steps={len(steps)}  OK")

        # ── Step 2: 执行 + 评审通过步骤1（任务进 EXECUTING）────
        log("STEP2", f"执行并评审通过步骤1 (id={step1['id']}) ...")
        execute_step(hdr, task_id, step1["id"])
        review_step(hdr, step1["id"], "PASS", "S115 E2E 自动评审")
        task = detail(hdr, task_id)
        if task["status"] not in (7, 8):
            raise FixtureError(f"预期任务 EXECUTING(7)/EXECUTED(8)，实际 {task['status']}")
        log("STEP2", f"任务状态={STATUS.get(task['status'])} completedSteps={task.get('completedSteps')}  OK")

        # ── Step 3: 执行 + 评审通过步骤2 ────────────────────────
        log("STEP3", f"执行并评审通过步骤2 (id={step2['id']}) ...")
        s2 = execute_step(hdr, task_id, step2["id"])
        if s2["status"] != 2:
            raise FixtureError(f"步骤2 状态 {s2['status']} 无法评审（期望自测通过 2）")
        review_step(hdr, step2["id"], "PASS", "S115 E2E 自动评审")
        s2 = next(s for s in step_list(hdr, task_id) if s["id"] == step2["id"])
        if s2["status"] != 6:
            raise FixtureError(f"步骤2 评审后应为已完成(6)，实际={s2['status']}")
        log("STEP3", f"步骤2 状态={STEP_STATUS.get(s2['status'])}  OK")

        troot = target_root.format(task_id=task_id)

        # ── Step 4: 批量落盘步骤2产物 ───────────────────────────
        log("STEP4", "批量落盘步骤2产物 ...")
        r = requests.post(f"{BASE}/migration/artifact/apply-step",
                          params={"stepId": step2["id"]}, headers=hdr, timeout=120)
        count = int(check(r, "批量落盘") or 0)
        if count <= 0:
            raise FixtureError("落盘产物数应 > 0")
        artifacts = artifact_list(hdr, step2["id"])
        if len(artifacts) != count:
            raise FixtureError(f"产物数不一致: {len(artifacts)} vs {count}")
        for a in artifacts:
            if not a["applied"]:
                raise FixtureError(f"产物 {a['relativePath']} applied 应为 true")
            fpath = os.path.join(troot, a["relativePath"])
            if not os.path.isfile(fpath):
                raise FixtureError(f"落盘文件不存在: {fpath}")
            if os.path.getsize(fpath) <= 0:
                raise FixtureError(f"落盘文件为空: {fpath}")
        log("STEP4", f"全部 {count} 个文件真实存在且 applied=true ✓")

        # ── Step 5: 撤销单个产物 ────────────────────────────────
        first = artifacts[0]
        fpath = os.path.join(troot, first["relativePath"])
        log("STEP5", f"撤销落盘单个产物 {first['relativePath']} ...")
        r = requests.post(f"{BASE}/migration/artifact/unapply",
                          params={"artifactId": first["id"]}, headers=hdr, timeout=60)
        check(r, "撤销落盘")
        if os.path.exists(fpath):
            raise FixtureError(f"撤销后文件仍存在: {fpath}")
        r = requests.get(f"{BASE}/migration/artifact/{first['id']}", headers=hdr, timeout=10)
        if check(r, "产物详情")["applied"] is not False:
            raise FixtureError("撤销后 applied 应为 false")
        log("STEP5", "撤销成功，文件已删除，applied=false ✓")

        # ── Step 6: 重新落盘单个产物 ────────────────────────────
        log("STEP6", "重新落盘单个产物 ...")
        apply_artifact(hdr, first["id"])
        if not os.path.isfile(fpath):
            raise FixtureError(f"重新落盘后文件不存在: {fpath}")
        log("STEP6", "重新落盘成功，文件恢复 ✓")

        # ── Step 7: 路径穿越防护 ────────────────────────────────
        log("STEP7", "路径穿越防护（直连 DB 注入 ../ 产物）...")
        evil_path = "../../../../tmp/migration_evil_test.txt"
        conn = connect_db()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO migration_artifact (task_id, step_id, artifact_type, relative_path, "
                    "generated_content, content_hash, applied, create_time) "
                    "VALUES (%s, %s, 'OTHER', %s, 'evil', 'x', 0, NOW())",
                    (task_id, step2["id"], evil_path))
                evil_id = cur.lastrowid
            conn.commit()
        finally:
            conn.close()
        apply_artifact(hdr, evil_id, expect_fail=True)
        if os.path.exists("/tmp/migration_evil_test.txt"):
            raise FixtureError("路径穿越文件被写出了！")
        conn = connect_db()
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM migration_artifact WHERE id = %s", (evil_id,))
            conn.commit()
        finally:
            conn.close()
        log("STEP7", "路径穿越被拦截，恶意记录已清理 ✓")

        # ── Step 8: 对照组（自建 CREATED 态任务）状态白名单 ─────
        log("STEP8", "对照组：自建 CREATED(0) 态任务，校验 complete/rollback 非法状态拦截 ...")
        ctrl_id = create_task(hdr, run_name("S115-ctrl"),
                              description="S115 对照组：CREATED 态，用于 complete/rollback 非法状态校验")
        ctrl = detail(hdr, ctrl_id)
        if ctrl["status"] != 0:
            raise FixtureError(f"对照组任务应为 CREATED(0)，实际={ctrl['status']}")
        for action in ("complete", "rollback"):
            r = requests.post(f"{BASE}/migration/task/{action}",
                              params={"taskId": ctrl_id}, headers=hdr, timeout=30)
            check(r, f"{action} 非法状态(CREATED)", expect_fail=True)
        log("STEP8", "状态白名单校验通过 ✓")

        print()
        print("=" * 60)
        print("  阶段8 产物落盘/撤销/路径穿越/状态机 E2E：全部通过 ✓")
        print(f"  主任务（自建）  : {task_id}，落盘产物 {count} 个")
        print(f"  对照组（自建）  : {ctrl_id}（CREATED 态已拒绝 complete/rollback）")
        print("=" * 60)
        return 0
    finally:
        if ctrl_id:
            cleanup(ctrl_id)
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
