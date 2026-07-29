-- =============================================================
-- PivotOS message 插件 · 建表（表前缀 msg_，tenant_id 预留）
-- 前缀登记：ArchUnit A8 表前缀白名单（pivotos-admin-server P0ArchitectureTest）
-- 审计字段由 starter-mybatis AuditMetaObjectHandler 自动填充
-- =============================================================

-- 消息主表：一次发送产生一条主记录
CREATE TABLE msg_message (
                             id          BIGINT       NOT NULL COMMENT '消息ID（雪花）',
                             title       VARCHAR(128) NOT NULL COMMENT '标题',
                             content     TEXT         NOT NULL COMMENT '内容',
                             msg_type    TINYINT      NOT NULL DEFAULT 1 COMMENT '消息类型（1通知 2公告 3待办）',
                             biz_type    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '业务类型（来源模块自定义）',
                             biz_id      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '业务ID（跳转定位）',
                             tenant_id   BIGINT       DEFAULT NULL COMMENT '租户ID（多租户预留，S14 生效）',
                             create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                             create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                             update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                             update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                             deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                             PRIMARY KEY (id),
                             KEY idx_tenant_type (tenant_id, msg_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '消息主表';

-- 用户消息表：消息 × 接收人的已读状态载体（用户消息列表查询主表）
CREATE TABLE msg_user_message (
                                  id          BIGINT       NOT NULL COMMENT '用户消息ID（雪花）',
                                  message_id  BIGINT       NOT NULL COMMENT '消息ID',
                                  user_id     BIGINT       NOT NULL COMMENT '接收人用户ID',
                                  read_status TINYINT      NOT NULL DEFAULT 0 COMMENT '已读状态（0未读 1已读）',
                                  read_time   DATETIME     DEFAULT NULL COMMENT '阅读时间',
                                  tenant_id   BIGINT       DEFAULT NULL COMMENT '租户ID（多租户预留，S14 生效）',
                                  create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                                  create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                                  update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                                  update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                                  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                                  PRIMARY KEY (id),
                                  -- 重发幂等：同一消息对同一接收人只允许一条
                                  UNIQUE KEY uk_message_user (message_id, user_id),
                                  -- 用户消息列表场景：(tenant_id, user_id, read_status)
                                  KEY idx_tenant_user_read (tenant_id, user_id, read_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户消息表';

-- 消息模板表：标题/内容模板，占位符 {var} 渲染
CREATE TABLE msg_template (
                              id            BIGINT       NOT NULL COMMENT '模板ID（雪花）',
                              template_code VARCHAR(64)  NOT NULL COMMENT '模板编码',
                              template_name VARCHAR(128) NOT NULL COMMENT '模板名称',
                              title_tpl     VARCHAR(255) NOT NULL COMMENT '标题模板',
                              content_tpl   TEXT         NOT NULL COMMENT '内容模板',
                              msg_type      TINYINT      NOT NULL DEFAULT 1 COMMENT '消息类型（1通知 2公告 3待办）',
                              channel       VARCHAR(16)  NOT NULL DEFAULT 'inbox' COMMENT '默认渠道（inbox/sms/email）',
                              status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
                              remark        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '备注',
                              tenant_id     BIGINT       DEFAULT NULL COMMENT '租户ID（多租户预留，S14 生效）',
                              create_by     BIGINT       DEFAULT NULL COMMENT '创建人',
                              create_time   DATETIME     DEFAULT NULL COMMENT '创建时间',
                              update_by     BIGINT       DEFAULT NULL COMMENT '更新人',
                              update_time   DATETIME     DEFAULT NULL COMMENT '更新时间',
                              deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                              PRIMARY KEY (id),
                              UNIQUE KEY uk_template_code (template_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '消息模板表';

-- 发送日志表：短信/邮件等外发渠道的发送留痕（站内信不落本表）
CREATE TABLE msg_send_log (
                              id           BIGINT       NOT NULL COMMENT '日志ID（雪花）',
                              message_id   BIGINT       DEFAULT NULL COMMENT '关联消息ID',
                              channel      VARCHAR(16)  NOT NULL COMMENT '渠道（sms/email）',
                              receiver     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '接收标识（手机号/邮箱/用户ID）',
                              title        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '标题快照',
                              send_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '发送状态（0成功 1失败）',
                              error_msg    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '失败原因',
                              tenant_id    BIGINT       DEFAULT NULL COMMENT '租户ID（多租户预留，S14 生效）',
                              create_by    BIGINT       DEFAULT NULL COMMENT '创建人',
                              create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
                              update_by    BIGINT       DEFAULT NULL COMMENT '更新人',
                              update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
                              deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                              PRIMARY KEY (id),
                              KEY idx_message_id (message_id),
                              KEY idx_tenant_channel (tenant_id, channel)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '消息发送日志表';
