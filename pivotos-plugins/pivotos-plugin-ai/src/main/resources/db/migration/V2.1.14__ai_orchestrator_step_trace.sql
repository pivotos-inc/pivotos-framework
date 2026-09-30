-- PivotOS ai 插件 · A5-2 编排可靠性（S117）
-- ai_tool_plan_step：步骤级执行轨迹（编排可观测）——每一步的工具/入参/尝试次数/耗时/状态/输出摘要/失败原因
-- ai_tool_plan：扩展计划级可靠性字段（重试次数 / 熔断标记 / 失败原因）
-- 表前缀 ai_ 已在 ArchUnit A8 白名单（pivotos-plugin-ai 域），无需新增登记
--
-- 为什么单开一张步骤表而不是把轨迹 JSON 塞进 ai_tool_plan 一列：
-- 「可观测」要求能按状态/工具/耗时做筛选与聚合，JSON 列只能看不能查。
-- ai_tool_invoke 已有每次调用的审计，但它记录的是「调用」而非「编排步骤」——
-- 重试会让同一步出现多行调用，步骤表给出的是步骤级终态（含尝试次数与熔断跳过）。

CREATE TABLE ai_tool_plan_step (
    id             BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    plan_id        BIGINT       NOT NULL COMMENT '所属编排计划 ID',
    step_no        INT          NOT NULL COMMENT '步骤序号（计划内唯一）',
    tool_name      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '工具名',
    write_flag     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否写操作（1 写 0 只读）',
    attempt_count  INT          NOT NULL DEFAULT 1 COMMENT '实际尝试次数（1 表示首次即成功）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'success' COMMENT '步骤终态（success 成功 / failed 失败 / need_confirm 待写操作确认 / skipped 被熔断跳过未发起调用）',
    args_json      TEXT         NULL COMMENT '渲染引用后的实际入参（审计复盘）',
    output_summary VARCHAR(2000) NULL COMMENT '步骤输出摘要（超长截断）',
    error_message  VARCHAR(1000) NULL COMMENT '失败原因（终态失败信号原文，超长截断）',
    cost_ms        BIGINT       NOT NULL DEFAULT 0 COMMENT '本步耗时（毫秒，含重试）',
    trace_id       VARCHAR(64)  NULL COMMENT '链路追踪 ID',
    tenant_id      BIGINT       NULL COMMENT '租户 ID',
    create_by      BIGINT       NULL COMMENT '创建人',
    create_time    DATETIME     NULL COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新人',
    update_time    DATETIME     NULL COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_plan_step_plan (plan_id),
    KEY idx_plan_step_status (status),
    KEY idx_plan_step_tool (tool_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 工具编排步骤轨迹（S117 A5-2 编排可观测）';

ALTER TABLE ai_tool_plan
    ADD COLUMN retry_count    INT          NOT NULL DEFAULT 0 COMMENT '本次执行累计重试次数（写步骤恒为 0）' AFTER cost_ms,
    ADD COLUMN circuit_broken TINYINT      NOT NULL DEFAULT 0 COMMENT '是否触发熔断（1 熔断：后续步骤未再发起调用）' AFTER retry_count,
    ADD COLUMN fail_reason    VARCHAR(1000) NULL COMMENT '失败原因（终态失败信号原文；与 result_summary 的区别是它只记失败信号）' AFTER circuit_broken;
