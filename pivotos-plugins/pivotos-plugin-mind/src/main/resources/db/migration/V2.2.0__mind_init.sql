-- =============================================================
-- PivotOS·智域（PivotMind）个人端插件表结构
-- 表前缀：mind_（ArchUnit A8 已登记 pivotos-plugin-mind -> mind_）
-- =============================================================

-- ========== 1. mind_knowledge 知识库表 ==========
CREATE TABLE IF NOT EXISTS mind_knowledge (
    id          BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    user_id     BIGINT        NOT NULL COMMENT '所属用户ID',
    title       VARCHAR(200)  NOT NULL COMMENT '标题',
    type        VARCHAR(20)   NOT NULL COMMENT '类型：note 笔记 / link 链接 / doc 文档',
    content     TEXT          NULL     COMMENT '内容',
    source_url  VARCHAR(500)  NULL     COMMENT '来源URL',
    tags        VARCHAR(200)  NULL     COMMENT '标签，逗号分隔',
    create_by   BIGINT        NULL,
    create_time DATETIME      NULL,
    update_by   BIGINT        NULL,
    update_time DATETIME      NULL,
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '智域知识库表';

-- ========== 2. mind_todo 待办表 ==========
CREATE TABLE IF NOT EXISTS mind_todo (
    id          BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    user_id     BIGINT        NOT NULL COMMENT '所属用户ID',
    title       VARCHAR(200)  NOT NULL COMMENT '待办标题',
    remark      VARCHAR(500)  NULL     COMMENT '备注',
    priority    VARCHAR(20)   NULL     COMMENT '优先级：low / medium / high',
    status      TINYINT       NOT NULL DEFAULT 0 COMMENT '状态（0未完成 1已完成）',
    due_time    DATETIME      NULL     COMMENT '计划完成时间',
    finish_time DATETIME      NULL     COMMENT '实际完成时间',
    create_by   BIGINT        NULL,
    create_time DATETIME      NULL,
    update_by   BIGINT        NULL,
    update_time DATETIME      NULL,
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '智域待办表';
