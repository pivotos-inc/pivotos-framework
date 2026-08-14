-- =============================================================
-- PivotOS ai 插件 · API Key 用途区分 + 供应商向量化模型（S61 动态 Embedding Key）
-- 1) ai_api_key 增加 purpose：区分 Key 用途（chat/embedding/all），
--    存量 Key 默认 all（对话+向量化通用），支持独立管理 Embedding Key
-- 2) ai_provider 增加 embedding_model：向量化模型名，
--    空则回退 spring.ai.openai.embedding.options.model 静态配置
-- =============================================================

-- Key 用途：chat=对话, embedding=向量化, all=通用（对话+向量化）
ALTER TABLE ai_api_key
    ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'all'
    COMMENT 'Key 用途（chat=对话, embedding=向量化, all=通用）' AFTER label;

-- 供应商向量化模型名（空则回退 spring.ai.openai.embedding.options.model）
ALTER TABLE ai_provider
    ADD COLUMN embedding_model VARCHAR(64) NOT NULL DEFAULT ''
    COMMENT '向量化模型名（空则回退 spring.ai.openai.embedding.options.model）' AFTER default_model;

-- 为 purpose 查询加索引（embedding 解析链按 purpose 过滤）
ALTER TABLE ai_api_key
    ADD INDEX idx_provider_purpose (provider_id, purpose, status);
