-- =============================================================
-- PivotOS ai-kb 插件 · RAG 增强二期（S62）
-- 1. ai_kb_chunk 文本块表（BM25 检索用）
-- 2. ai_kb_base 加 hybrid_search 列（混合检索开关）
-- =============================================================

-- ========== 1. ai_kb_chunk 文本块表 ==========
CREATE TABLE IF NOT EXISTS ai_kb_chunk (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    kb_id         BIGINT        NOT NULL COMMENT '知识库ID',
    doc_id        BIGINT        NOT NULL COMMENT '文档ID',
    chunk_index   INT           NOT NULL DEFAULT 0 COMMENT '块序号',
    content       TEXT          NOT NULL COMMENT '文本块原文',
    content_hash  CHAR(32)      NOT NULL DEFAULT '' COMMENT '内容MD5（前100字，去重用）',
    tenant_id     BIGINT        NULL     COMMENT '租户ID',
    create_time   DATETIME      NULL     COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_kb_doc (kb_id, doc_id),
    KEY idx_kb_hash (kb_id, content_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识库文本块（BM25检索用）';

-- ========== 2. ai_kb_base 加 hybrid_search 列 ==========
ALTER TABLE ai_kb_base
    ADD COLUMN hybrid_search TINYINT NOT NULL DEFAULT 1
    COMMENT '混合检索开关（0仅向量 1向量+BM25+RRF）' AFTER chunk_overlap;
