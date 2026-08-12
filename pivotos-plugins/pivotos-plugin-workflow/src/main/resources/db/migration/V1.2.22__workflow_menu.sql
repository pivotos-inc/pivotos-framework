-- ================================================================
-- S56 · 流程管理子菜单（2.5-F2 流程定义与管理页面）
-- ================================================================

-- 流程管理（menu_id=3000）由 V1.2.21 创建，本脚本追加子菜单和按钮权限

-- 3100 流程定义（二级菜单）
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3100, 3000, '流程定义', 'menu', '/workflow/definition', 'workflow/definition/index',
        'workflow:definition:list', 'i-ep-document', 0, 0, 1);

-- 3101~3106 按钮权限
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3101, 3100, '查询流程定义', 'button', NULL, NULL, 'workflow:definition:query', NULL, 0, 0, 1);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3102, 3100, '发布流程', 'button', NULL, NULL, 'workflow:definition:publish', NULL, 0, 0, 2);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3103, 3100, '编辑流程', 'button', NULL, NULL, 'workflow:definition:edit', NULL, 0, 0, 3);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3104, 3100, '删除流程', 'button', NULL, NULL, 'workflow:definition:remove', NULL, 0, 0, 4);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3105, 3100, '流程设计器', 'button', NULL, NULL, 'workflow:definition:design', NULL, 0, 0, 5);
