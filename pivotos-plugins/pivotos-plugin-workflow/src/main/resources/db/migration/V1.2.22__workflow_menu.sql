-- ================================================================
-- S56 · 流程管理子菜单（2.5-F2 流程定义与管理页面）
-- ================================================================

-- 流程管理（id=3500）由 V1.2.21 创建，本脚本追加子菜单和按钮权限

-- 3510 流程定义（二级菜单）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3510, 3500, '流程定义', 'C', 'definition', 'workflow/definition/index',
        'workflow:definition:list', 'document', 1, 0, 0, 1, NOW(), 0);

-- 3511~3515 按钮权限
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3511, 3510, '查询流程定义', 'F', 'workflow:definition:query', 1, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3512, 3510, '发布流程', 'F', 'workflow:definition:publish', 2, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3513, 3510, '编辑流程', 'F', 'workflow:definition:edit', 3, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3514, 3510, '删除流程', 'F', 'workflow:definition:remove', 4, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3515, 3510, '流程设计器', 'F', 'workflow:definition:design', 5, 0, 0, 1, NOW(), 0);
