-- =============================================================
-- PivotOS ai-kb 插件 · 检索评测跑分记录与逐题明细（S67）
-- 表前缀：ai_kb_（ArchUnit A8 已登记 pivotos-plugin-ai-kb -> ai_kb_）
-- =============================================================

CREATE TABLE IF NOT EXISTS ai_kb_eval_record (
    id                  BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    kb_id               BIGINT       NOT NULL COMMENT '关联知识库ID',
    question_count      INT          NOT NULL COMMENT '本轮跑分题数',
    baseline_hit        INT          NOT NULL COMMENT '基线（rerank 关）命中题数',
    rerank_hit          INT          NOT NULL COMMENT '重排（rerank 开）命中题数',
    baseline_hit_rate   DECIMAL(5,4) NOT NULL COMMENT '基线 Hit@K（命中数/题数）',
    rerank_hit_rate     DECIMAL(5,4) NOT NULL COMMENT '重排 Hit@K',
    baseline_mrr        DECIMAL(6,4) NOT NULL COMMENT '基线 MRR（avg(1/rank)，未命中计 0）',
    rerank_mrr          DECIMAL(6,4) NOT NULL COMMENT '重排 MRR',
    order_changed_count INT          NOT NULL COMMENT '改序题数（两配置 topK 序列不一致）',
    tenant_id           BIGINT       NULL     COMMENT '租户ID',
    create_by           BIGINT       NULL,
    create_time         DATETIME     NULL,
    update_by           BIGINT       NULL,
    update_time         DATETIME     NULL,
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_kb_id (kb_id),
    KEY idx_tenant_id (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识库检索评测跑分记录（S67）';

CREATE TABLE IF NOT EXISTS ai_kb_eval_record_item (
    id               BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    record_id        BIGINT       NOT NULL COMMENT '关联跑分记录ID',
    kb_id            BIGINT       NOT NULL COMMENT '关联知识库ID',
    question_id      BIGINT       NULL     COMMENT '原评测问题ID（问题可能已被删除）',
    question         VARCHAR(500) NOT NULL COMMENT '评测问题快照',
    expected_keyword VARCHAR(200) NOT NULL COMMENT '预期命中关键词快照',
    baseline_rank    INT          NOT NULL DEFAULT 0 COMMENT '基线首次命中排名（1-based，0=未命中）',
    rerank_rank      INT          NOT NULL DEFAULT 0 COMMENT '重排首次命中排名（0=未命中）',
    order_changed    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否改序（0否 1是）',
    tenant_id        BIGINT       NULL     COMMENT '租户ID',
    create_by        BIGINT       NULL,
    create_time      DATETIME     NULL,
    update_by        BIGINT       NULL,
    update_time      DATETIME     NULL,
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_record_id (record_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识库检索评测跑分逐题明细（快照，S67）';
