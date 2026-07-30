-- =============================================================
-- PivotOS ai 插件 · 模型供应商与 API Key 管理（后台可维护，替代纯环境变量配置）
-- ai_provider：OpenAI 兼容供应商（base_url 须含 /v1，Spring AI 2.0 官方 SDK 约定）
-- ai_api_key：供应商下多 Key（AES 加密落库，轮询负载 + 失败切换）
-- tenant_id 预留：未来支持租户级 AI 配置 + 平台级兜底
-- =============================================================

-- AI 模型供应商表
CREATE TABLE ai_provider (
                             id            BIGINT       NOT NULL COMMENT '供应商ID（雪花，种子数据用字面 ID）',
                             name          VARCHAR(64)  NOT NULL COMMENT '供应商名称',
                             code          VARCHAR(64)  NOT NULL COMMENT '供应商编码（deleted=0 范围内唯一，service 层查重）',
                             base_url      VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容 base-url（须含 /v1）',
                             default_model VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '默认模型（未显式选择时兜底）',
                             sort          INT          NOT NULL DEFAULT 0 COMMENT '排序（越小越靠前，默认供应商取启用中最靠前者）',
                             status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0启用 1停用）',
                             remark        VARCHAR(255) DEFAULT NULL COMMENT '备注',
                             tenant_id     BIGINT       DEFAULT NULL COMMENT '租户ID（多租户预留：租户级配置+平台兜底）',
                             create_by     BIGINT       DEFAULT NULL COMMENT '创建人',
                             create_time   DATETIME     DEFAULT NULL COMMENT '创建时间',
                             update_by     BIGINT       DEFAULT NULL COMMENT '更新人',
                             update_time   DATETIME     DEFAULT NULL COMMENT '更新时间',
                             deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                             PRIMARY KEY (id),
                             KEY idx_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 模型供应商表';

-- AI API Key 表（同供应商多 Key 轮询负载，api_key 列 AES 加密存储）
CREATE TABLE ai_api_key (
                            id          BIGINT       NOT NULL COMMENT 'KeyID（雪花）',
                            provider_id BIGINT       NOT NULL COMMENT '归属供应商ID',
                            label       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '备注名（如"生产主 Key"）',
                            api_key     VARCHAR(512) NOT NULL COMMENT 'API Key（FieldEncryptTypeHandler AES 加密，Base64 密文）',
                            status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0启用 1停用）',
                            tenant_id   BIGINT       DEFAULT NULL COMMENT '租户ID（多租户预留）',
                            create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                            create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                            update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                            update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                            deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                            PRIMARY KEY (id),
                            -- 轮询取 Key 场景：(provider_id, status)
                            KEY idx_provider (provider_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI API Key 表';

-- 种子数据：阿里云百炼 DashScope（S19 环境变量方案平滑迁移，Key 由管理员后台录入）
INSERT INTO ai_provider (id, name, code, base_url, default_model, sort, status, remark, create_by, create_time, deleted)
VALUES (1, '阿里云百炼', 'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-plus', 1, 0,
        'OpenAI 兼容模式接入 qwen 系列，Key 在「AI 配置」页维护', 1, NOW(), 0);
