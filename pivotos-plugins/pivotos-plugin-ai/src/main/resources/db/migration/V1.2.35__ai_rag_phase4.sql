-- S68 RAG 四期：引用溯源与查询改写
-- 1. ai_chat_message 增加 RAG 引用来源快照（JSON，重开会话历史引用不丢失）
-- 2. ai_kb_base 增加知识库级查询改写开关（默认关，与 rerank 并列）

ALTER TABLE ai_chat_message
    ADD COLUMN `references` JSON NULL
    COMMENT 'RAG 引用来源快照（JSON，仅 assistant 消息且使用知识库时有值，S68）' AFTER content;

ALTER TABLE ai_kb_base
    ADD COLUMN query_rewrite TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '查询改写开关（1=检索前 LLM 改写多轮问题为独立检索语句，S68）' AFTER rerank;
