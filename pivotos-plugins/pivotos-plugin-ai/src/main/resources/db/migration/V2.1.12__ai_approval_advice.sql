-- PivotOS ai 插件 · A3 AI 审批助手建议留痕（S101）
-- ai_approval_advice：每次建议生成落一条——结构化解析结果（结论/理由/引用）+ 模型原文防篡改
-- 表前缀 ai_ 已在 ArchUnit A8 白名单（pivotos-plugin-ai 域），无需新增登记

CREATE TABLE ai_approval_advice (
    id              BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    task_id         BIGINT        NOT NULL COMMENT '待办任务 ID（warm-flow flow_task.id）',
    instance_id     BIGINT        NOT NULL COMMENT '流程实例 ID（warm-flow flow_instance.id）',
    user_id         BIGINT        NULL COMMENT '请求人 ID（当前审批人）',
    kb_id           BIGINT        NULL COMMENT '检索所用知识库 ID（未检索/无默认库为 NULL）',
    conclusion      VARCHAR(16)   NOT NULL DEFAULT 'need_info' COMMENT '结论（approve 建议通过 / reject 建议驳回 / need_info 需补充材料）',
    reason          TEXT          NULL COMMENT '结论理由（结构化解析失败时为原文降级）',
    references_json TEXT          NULL COMMENT '制度依据引用（JSON 数组：chunkId/fileName/quote，无引用为 []）',
    raw_content     TEXT          NULL COMMENT '模型输出原文（防篡改留痕，解析失败也落原文）',
    provider        VARCHAR(64)   NULL COMMENT '供应商编码（ai_provider.provider_code）',
    model           VARCHAR(128)  NULL COMMENT '模型名（本次生成实际使用的模型）',
    cost_ms         BIGINT        NOT NULL DEFAULT 0 COMMENT '生成耗时（毫秒）',
    tenant_id       BIGINT        NULL COMMENT '租户 ID',
    create_by       BIGINT        NULL COMMENT '创建人',
    create_time     DATETIME      NULL COMMENT '创建时间',
    update_by       BIGINT        NULL COMMENT '更新人',
    update_time     DATETIME      NULL COMMENT '更新时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_advice_task (task_id),
    KEY idx_advice_instance (instance_id),
    KEY idx_advice_user (user_id),
    KEY idx_advice_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 审批建议留痕（S101 A3）';
