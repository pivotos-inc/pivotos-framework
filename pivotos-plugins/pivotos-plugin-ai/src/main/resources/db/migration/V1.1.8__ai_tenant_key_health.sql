-- =============================================================
-- PivotOS ai 插件 · 多租户 AI 配置 + Key 健康度（S23）
-- 1) ai_provider / ai_api_key 启用 tenant_id 行级隔离：
--    0 = 平台/默认租户（租户无自有配置时的兜底来源），存量 NULL 回填为 0
-- 2) ai_api_key 增加 fail_count：连续失败计数，达到阈值
--    （pivotos.ai.key-fail-threshold，默认 3）自动停用并站内信告警
-- =============================================================

-- 存量数据归属平台租户
UPDATE ai_provider SET tenant_id = 0 WHERE tenant_id IS NULL;
UPDATE ai_api_key SET tenant_id = 0 WHERE tenant_id IS NULL;

-- tenant_id 语义固化：0=平台/默认租户，行级过滤参与列
ALTER TABLE ai_provider
    MODIFY COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID（0=平台/默认租户，租户配置优先→平台兜底）',
    ADD INDEX idx_tenant (tenant_id, status);

ALTER TABLE ai_api_key
    MODIFY COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID（0=平台/默认租户，随归属供应商）',
    ADD COLUMN fail_count INT NOT NULL DEFAULT 0 COMMENT '连续失败次数（成功清零，达阈值自动停用）' AFTER status;
