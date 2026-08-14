-- =============================================================
-- PivotOS ai-kb 插件 · 检索评测问题集（S66）
-- 表前缀：ai_kb_（ArchUnit A8 已登记 pivotos-plugin-ai-kb -> ai_kb_）
-- =============================================================

CREATE TABLE IF NOT EXISTS ai_kb_eval_question (
    id               BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    kb_id            BIGINT        NOT NULL COMMENT '关联知识库ID',
    question         VARCHAR(500)  NOT NULL COMMENT '评测问题',
    expected_keyword VARCHAR(200)  NOT NULL COMMENT '预期命中关键词（命中=topK 任一结果内容包含该词，忽略大小写）',
    sort             INT           NOT NULL DEFAULT 0 COMMENT '排序',
    tenant_id        BIGINT        NULL     COMMENT '租户ID',
    create_by        BIGINT        NULL,
    create_time      DATETIME      NULL,
    update_by        BIGINT        NULL,
    update_time      DATETIME      NULL,
    deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_kb_id (kb_id),
    KEY idx_tenant_id (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识库检索评测问题集（S66）';
