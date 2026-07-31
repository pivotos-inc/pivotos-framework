-- =============================================================
-- PivotOS ai 插件 · 建表（表前缀 ai_，tenant_id 预留）
-- 前缀登记：ArchUnit A8 表前缀白名单（pivotos-admin-server P0ArchitectureTest）
-- 审计字段由 starter-mybatis AuditMetaObjectHandler 自动填充
-- =============================================================

-- AI 会话表：用户 × 多轮对话的容器
CREATE TABLE ai_conversation (
                                 id          BIGINT       NOT NULL COMMENT '会话ID（雪花）',
                                 user_id     BIGINT       NOT NULL COMMENT '归属用户ID',
                                 title       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '会话标题',
                                 model       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '模型标识（如 qwen-plus）',
                                 tenant_id   BIGINT       DEFAULT NULL COMMENT '租户ID（多租户预留）',
                                 create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                                 create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                                 update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                                 update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                                 deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                                 PRIMARY KEY (id),
                                 -- 我的会话列表场景：(tenant_id, user_id)
                                 KEY idx_tenant_user (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 会话表';

-- AI 对话消息表：会话内的单条 user/assistant 消息
CREATE TABLE ai_chat_message (
                                 id              BIGINT      NOT NULL COMMENT '消息ID（雪花）',
                                 conversation_id BIGINT      NOT NULL COMMENT '会话ID',
                                 user_id         BIGINT      NOT NULL COMMENT '归属用户ID（冗余，越权校验免联表）',
                                 role            VARCHAR(16) NOT NULL COMMENT '角色（user/assistant）',
                                 content         MEDIUMTEXT  NOT NULL COMMENT '消息内容',
                                 tenant_id       BIGINT      DEFAULT NULL COMMENT '租户ID（多租户预留）',
                                 create_by       BIGINT      DEFAULT NULL COMMENT '创建人',
                                 create_time     DATETIME    DEFAULT NULL COMMENT '创建时间',
                                 update_by       BIGINT      DEFAULT NULL COMMENT '更新人',
                                 update_time     DATETIME    DEFAULT NULL COMMENT '更新时间',
                                 deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '删除标记',
                                 PRIMARY KEY (id),
                                 -- 会话历史加载场景：(conversation_id, id)
                                 KEY idx_conversation (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 对话消息表';
