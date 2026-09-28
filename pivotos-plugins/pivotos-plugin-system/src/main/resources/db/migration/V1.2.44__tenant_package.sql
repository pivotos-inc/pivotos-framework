-- =============================================================
-- V1.2.44：S106 C2 租户管理面——租户套餐表 + 套餐管理菜单
-- 套餐 = 功能开关集合（14 号清单决策项）：menu_ids 存可用菜单 ID 集合（JSON 数组），
-- 不碰计费；权限仍走既有角色体系，套餐只控「看不看得见」。
-- 逻辑删除表不设 DB 唯一键（踩坑 24 口径：复活撞键），package_name 唯一性走应用层校验。
-- =============================================================

CREATE TABLE IF NOT EXISTS `sys_tenant_package` (
    `id`          BIGINT(20)   NOT NULL                COMMENT '主键（雪花 ID）',
    `package_name` VARCHAR(64) NOT NULL                COMMENT '套餐名称',
    `menu_ids`    TEXT         DEFAULT NULL            COMMENT '菜单范围（JSON 数组，sys_menu.id 集合；NULL=不限制）',
    `status`      TINYINT(4)   NOT NULL DEFAULT 0      COMMENT '状态：0 正常 1 停用',
    `remark`      VARCHAR(500) DEFAULT NULL            COMMENT '备注',
    `create_by`   BIGINT(20)   DEFAULT NULL            COMMENT '创建人',
    `create_time` DATETIME     DEFAULT NULL            COMMENT '创建时间',
    `update_by`   BIGINT(20)   DEFAULT NULL            COMMENT '更新人',
    `update_time` DATETIME     DEFAULT NULL            COMMENT '更新时间',
    `deleted`     TINYINT(4)   NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户套餐（功能开关集合）';

-- 租户套餐菜单（系统管理 1000 下；1160 段留给租户管理，见 V1.2.45）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1170, 1000, '租户套餐', 'C', 'tenantPackage', 'system/tenantPackage/index', 'system:tenant-package:list',   'box', 'pc', 11, 0, 0, 1, NOW(), 0),
    (1171, 1170, '套餐查询', 'F', '',              '',                           'system:tenant-package:query',  '',    'pc', 1,  0, 0, 1, NOW(), 0),
    (1172, 1170, '套餐新增', 'F', '',              '',                           'system:tenant-package:add',    '',    'pc', 2,  0, 0, 1, NOW(), 0),
    (1173, 1170, '套餐修改', 'F', '',              '',                           'system:tenant-package:edit',   '',    'pc', 3,  0, 0, 1, NOW(), 0),
    (1174, 1170, '套餐删除', 'F', '',              '',                           'system:tenant-package:remove', '',    'pc', 4,  0, 0, 1, NOW(), 0);
