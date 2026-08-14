-- =============================================================
-- PivotOS ai-kb 插件 · S62 自测补漏
-- ai_kb_document 加 chunk_count 列（文档分块数量，前端 KbDocumentVO.chunkCount 依赖）
-- =============================================================

ALTER TABLE ai_kb_document
    ADD COLUMN chunk_count INT NOT NULL DEFAULT 0
    COMMENT '分块数量（S62 混合检索）' AFTER chunk_overlap;
