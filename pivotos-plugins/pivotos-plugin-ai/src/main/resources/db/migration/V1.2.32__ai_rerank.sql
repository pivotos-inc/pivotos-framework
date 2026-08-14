-- S65 RAG 增强三期：reranker 重排
-- 1. ai_provider 增加重排模型配置（空则不启用重排），Key 复用 embedding/all 用途
-- 2. ai_kb_base 增加知识库级重排开关（默认开，与 hybrid_search 并列）

ALTER TABLE ai_provider
    ADD COLUMN rerank_model VARCHAR(100) NULL
    COMMENT '重排模型名（如 qwen3-rerank，空则不启用重排，S65）' AFTER embedding_model;

ALTER TABLE ai_kb_base
    ADD COLUMN rerank TINYINT(1) NOT NULL DEFAULT 1
    COMMENT '重排开关（1=RRF 融合后经 reranker 精排，S65）' AFTER hybrid_search;
