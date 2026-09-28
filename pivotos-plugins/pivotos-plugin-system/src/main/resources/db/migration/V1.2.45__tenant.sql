-- =============================================================
-- V1.2.45：S106 C2 租户管理面——租户表 + 租户管理菜单
-- 03 库设文档 §3.2 口径：code、name、套餐 id、账号数上限、过期时间、状态。
-- tenant_code 不设 DB 唯一键（踩坑 24 口径：逻辑删除复活撞键），唯一性走应用层校验。
-- 共享库口径（14 号清单决策项）：独立库初始化随 P5 环境就绪再议。
-- =============================================================

CREATE TABLE IF NOT EXISTS `sys_tenant` (
    `id`            BIGINT(20)   NOT NULL                COMMENT '主键（雪花 ID）',
    `tenant_code`   VARCHAR(64)  NOT NULL                COMMENT '租户编码',
    `tenant_name`   VARCHAR(64)  NOT NULL                COMMENT '租户名称',
    `package_id`    BIGINT(20)   DEFAULT NULL            COMMENT '套餐 ID（sys_tenant_package.id；NULL=平台代管，不做套餐过滤）',
    `account_limit` INT(11)      NOT NULL DEFAULT 0      COMMENT '账号数上限（0=不限）',
    `expire_time`   DATETIME     DEFAULT NULL            COMMENT '过期时间（NULL=永不过期）',
    `status`        TINYINT(4)   NOT NULL DEFAULT 0      COMMENT '状态：0 正常 1 停用',
    `remark`        VARCHAR(500) DEFAULT NULL            COMMENT '备注',
    `create_by`     BIGINT(20)   DEFAULT NULL            COMMENT '创建人',
    `create_time`   DATETIME     DEFAULT NULL            COMMENT '创建时间',
    `update_by`     BIGINT(20)   DEFAULT NULL            COMMENT '更新人',
    `update_time`   DATETIME     DEFAULT NULL            COMMENT '更新时间',
    `deleted`       TINYINT(4)   NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_code` (`tenant_code`),
    KEY `idx_package_id` (`package_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户（SaaS 多租户管理面）';

-- 租户管理菜单（系统管理 1000 下，1160 段；排序在租户套餐 1170 之前）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1160, 1000, '租户管理',   'C', 'tenant', 'system/tenant/index', 'system:tenant:list',   'office-building', 'pc', 10, 0, 0, 1, NOW(), 0),
    (1161, 1160, '租户查询',   'F', '',       '',                    'system:tenant:query',  '',                'pc', 1,  0, 0, 1, NOW(), 0),
    (1162, 1160, '租户新增',   'F', '',       '',                    'system:tenant:add',    '',                'pc', 2,  0, 0, 1, NOW(), 0),
    (1163, 1160, '租户修改',   'F', '',       '',                    'system:tenant:edit',   '',                'pc', 3,  0, 0, 1, NOW(), 0),
    (1164, 1160, '租户删除',   'F', '',       '',                    'system:tenant:remove', '',                'pc', 4,  0, 0, 1, NOW(), 0),
    (1165, 1160, '初始化向导', 'F', '',       '',                    'system:tenant:init',   '',                'pc', 5,  0, 0, 1, NOW(), 0);
