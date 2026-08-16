-- PivotOS ai 插件 · AI Token 用量计量（S92）
-- ai_usage：每次 ChatModel/EmbeddingModel 调用落一条（含失败调用，token 记 0）
-- 口径：供应商未返回 usage 时记 0（「未计量」），不因计量阻断业务链路
CREATE TABLE ai_usage (
    id                BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    user_id           BIGINT       NULL COMMENT '调用用户 ID（未登录链路为 NULL）',
    tenant_id         BIGINT       NULL COMMENT '租户 ID（随调用上下文，平台为 0/NULL）',
    provider_id       BIGINT       NULL COMMENT '供应商 ID（静态兜底 client 为 NULL）',
    provider_code     VARCHAR(32)  NULL COMMENT '供应商编码冗余（静态兜底记 static）',
    key_id            BIGINT       NULL COMMENT 'API Key ID（静态兜底为 NULL）',
    model             VARCHAR(128) NOT NULL DEFAULT '' COMMENT '本次实际使用模型名',
    scene             VARCHAR(16)  NOT NULL DEFAULT 'other' COMMENT '业务场景（chat/rag/coding/chart/other）',
    call_type         VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT '调用类型（chat=对话模型, embedding=向量化模型）',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT '提示词 token 数',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '生成 token 数',
    total_tokens      INT          NOT NULL DEFAULT 0 COMMENT '总 token 数',
    failed            TINYINT      NOT NULL DEFAULT 0 COMMENT '调用是否失败（0成功 1失败）',
    create_by         BIGINT       NULL COMMENT '创建人',
    create_time       DATETIME     NULL COMMENT '创建时间',
    update_by         BIGINT       NULL COMMENT '更新人',
    update_time       DATETIME     NULL COMMENT '更新时间',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_usage_time (create_time),
    KEY idx_usage_provider (provider_code, key_id),
    KEY idx_usage_user (user_id),
    KEY idx_usage_scene (scene)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI Token 用量记录（S92）';

-- 用量监控菜单：AI 助手(3000) 下新增 3050，权限点 ai:usage:list
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3050, 3000, '用量监控', 'C', 'usage', 'ai/usage/index', 'ai:usage:list', 'data-analysis', 5, 0, 0, 1, NOW(), 0);
