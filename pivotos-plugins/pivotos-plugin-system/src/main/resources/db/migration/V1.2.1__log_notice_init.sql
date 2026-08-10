-- =============================================================
-- PivotOS system 插件 · 登录日志 / 操作日志 / 通知公告（S26 2.1-F1/F2）
-- 前缀登记：ArchUnit A8 白名单 pivotos-plugin-system → sys_
-- 多租户：三表为平台共享表（S14 D1 决议同类），已登记
--         TenantProperties.BUILTIN_IGNORE_TABLES，不带 tenant_id 列
-- 审计字段由 starter-mybatis AuditMetaObjectHandler 自动填充
-- =============================================================

-- 登录日志（成功/失败都记，认证链路埋点写入）
CREATE TABLE sys_login_log (
    id          BIGINT       NOT NULL COMMENT '日志ID（雪花）',
    username    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '登录账号',
    ip          VARCHAR(64)  DEFAULT NULL COMMENT '登录IP（X-Forwarded-For 优先）',
    user_agent  VARCHAR(512) DEFAULT NULL COMMENT '浏览器 UA（截断 512）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '结果（0成功 1失败）',
    msg         VARCHAR(255) DEFAULT NULL COMMENT '提示消息（失败原因等）',
    login_time  DATETIME     NOT NULL COMMENT '登录时间',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (id),
    KEY idx_username_time (username, login_time),
    KEY idx_login_time (login_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '登录日志表';

-- 操作日志（@Log 注解 + OperLogAspect 切面采集，入参摘要已脱敏）
CREATE TABLE sys_oper_log (
    id             BIGINT        NOT NULL COMMENT '日志ID（雪花）',
    module         VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '功能模块（@Log.module）',
    oper_type      VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '操作类型（新增/修改/删除/发布/撤回等）',
    oper_name      VARCHAR(64)   DEFAULT NULL COMMENT '操作人用户名',
    oper_user_id   BIGINT        DEFAULT NULL COMMENT '操作人ID',
    method         VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '目标方法（类.方法）',
    request_method VARCHAR(16)   DEFAULT NULL COMMENT 'HTTP 方法',
    request_url    VARCHAR(255)  DEFAULT NULL COMMENT '请求 URL',
    request_params VARCHAR(2048) DEFAULT NULL COMMENT '入参摘要（脱敏 + 截断 2000）',
    status         TINYINT       NOT NULL DEFAULT 0 COMMENT '结果（0成功 1失败）',
    error_msg      VARCHAR(512)  DEFAULT NULL COMMENT '异常信息（失败时，截断 512）',
    duration       BIGINT        DEFAULT NULL COMMENT '耗时（毫秒）',
    oper_time      DATETIME      NOT NULL COMMENT '操作时间',
    create_by      BIGINT        DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_by      BIGINT        DEFAULT NULL COMMENT '更新人',
    update_time    DATETIME      DEFAULT NULL COMMENT '更新时间',
    deleted        TINYINT       NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (id),
    KEY idx_oper_time (oper_time),
    KEY idx_module_time (module, oper_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '操作日志表';

-- 通知公告（独立于 message 站内信，2.1-F2）
CREATE TABLE sys_notice (
    id           BIGINT       NOT NULL COMMENT '公告ID（雪花）',
    title        VARCHAR(128) NOT NULL COMMENT '公告标题',
    notice_type  TINYINT      NOT NULL DEFAULT 1 COMMENT '类型（1通知 2公告）',
    content      MEDIUMTEXT   COMMENT '富文本内容（HTML）',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0草稿 1已发布 2已撤回）',
    publish_time DATETIME     DEFAULT NULL COMMENT '发布时间',
    remark       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    create_by    BIGINT       DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by    BIGINT       DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (id),
    KEY idx_status_publish (status, publish_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '通知公告表';
