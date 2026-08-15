-- =============================================================
-- V1.2.37：S78 工作流深化二期——抄送（CC）表 + 加签/抄送菜单
-- 抄送为 warm-flow 引擎外能力，自建 flow_cc 表（flow_ 前缀已在 A8 白名单登记）。
-- 加签走 warm-flow 原生 TaskService.addSignature（flow_user + his_task 引擎留痕），无需建表。
-- =============================================================

-- ---------------- flow_cc 流程抄送记录 ----------------
-- flow_name / creator_name 为冗余字段：禁跨前缀联表（sys_user 不可 join），落库时写入。
CREATE TABLE IF NOT EXISTS flow_cc (
    id           BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    instance_id  BIGINT       NOT NULL COMMENT '流程实例 ID（flow_instance.id）',
    user_id      BIGINT       NOT NULL COMMENT '抄送收件人用户 ID',
    flow_name    VARCHAR(128) DEFAULT NULL COMMENT '流程名称（冗余，落库时写入）',
    creator_name VARCHAR(64)  DEFAULT NULL COMMENT '发起人昵称（冗余，落库时写入）',
    read_flag    TINYINT      NOT NULL DEFAULT 0 COMMENT '已读标记：0 未读 1 已读',
    read_time    DATETIME     DEFAULT NULL COMMENT '阅读时间',
    tenant_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '租户 ID',
    create_by    BIGINT       DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by    BIGINT       DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (id),
    KEY idx_flow_cc_user (user_id, read_flag),
    KEY idx_flow_cc_instance (instance_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '流程抄送记录（S78 工作流深化二期）';

-- ---------------- 菜单：加签按钮 + 抄送我的 ----------------
-- 3525 加签按钮（挂 3520 我的待办下，V1.2.23 创建）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, device, sort, visible, status, create_by, create_time, deleted)
VALUES (3525, 3520, '加签', 'F', 'workflow:task:add-signature', 'pc', 5, 0, 0, 1, NOW(), 0);

-- 3550 抄送我的（二级菜单，挂 3500 流程管理下）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted)
VALUES (3550, 3500, '抄送我的', 'C', 'cc', 'workflow/cc/index', 'workflow:cc:list', 'message', 'pc', 5, 0, 0, 1, NOW(), 0);
