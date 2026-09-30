-- PivotOS ai 插件 · A5-1 工具多步编排（S116）
-- ai_tool_plan：一次编排的计划级记录（意图 / 计划 JSON / 状态机 / 摘要）
-- ai_tool_invoke：扩展编排维度（plan_id + step_no），使审计能还原一条完整调用链
-- 表前缀 ai_ 已在 ArchUnit A8 白名单（pivotos-plugin-ai 域），无需新增登记

CREATE TABLE ai_tool_plan (
    id             BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    intent         VARCHAR(500) NOT NULL DEFAULT '' COMMENT '用户原始意图',
    goal           VARCHAR(500) NOT NULL DEFAULT '' COMMENT '计划目标（LLM 复述）',
    plan_json      TEXT         NULL COMMENT '计划 JSON（ToolPlan 序列化产物）',
    step_count     INT          NOT NULL DEFAULT 0 COMMENT '计划步骤数',
    status         VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT '状态（draft 待确认 / success 已完成 / need_confirm 等待写操作确认 / failed 中断）',
    executed_steps INT          NOT NULL DEFAULT 0 COMMENT '已成功执行的步骤数',
    blocked_step   INT          NOT NULL DEFAULT 0 COMMENT '被写操作确认闸拦下的步骤序号（0 未拦停）',
    result_summary VARCHAR(1000) NULL COMMENT '结果摘要 / 失败原因',
    cost_ms        BIGINT       NOT NULL DEFAULT 0 COMMENT '总耗时（毫秒）',
    trace_id       VARCHAR(64)  NULL COMMENT '链路追踪 ID',
    tenant_id      BIGINT       NULL COMMENT '租户 ID',
    create_by      BIGINT       NULL COMMENT '创建人',
    create_time    DATETIME     NULL COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新人',
    update_time    DATETIME     NULL COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_plan_status (status),
    KEY idx_plan_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 工具编排计划（S116 A5-1）';

ALTER TABLE ai_tool_invoke
    ADD COLUMN plan_id BIGINT NULL COMMENT '所属编排计划 ID（非编排调用为 NULL）' AFTER tool_name,
    ADD COLUMN step_no INT NULL COMMENT '编排步骤序号（非编排调用为 NULL）' AFTER plan_id,
    ADD KEY idx_invoke_plan (plan_id);
