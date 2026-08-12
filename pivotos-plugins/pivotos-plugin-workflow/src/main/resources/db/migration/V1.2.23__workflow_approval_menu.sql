-- ================================================================
-- S57 · 审批域子菜单（2.5-F3 审批流转）
-- ================================================================

-- 流程管理（menu_id=3000）由 V1.2.21 创建
-- 流程定义（menu_id=3100~3105）由 V1.2.22 创建
-- 本脚本追加审批域子菜单：我的待办 / 我的已办 / 我发起的

-- 3200 我的待办（二级菜单）
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3200, 3000, '我的待办', 'menu', '/workflow/task/pending', 'workflow/task/pending/index',
        'workflow:task:pending', 'i-ep-edit-pen', 0, 0, 2);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3201, 3200, '审批操作', 'button', NULL, NULL, 'workflow:task:approve', NULL, 0, 0, 1);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3202, 3200, '转办', 'button', NULL, NULL, 'workflow:task:transfer', NULL, 0, 0, 2);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3203, 3200, '委派', 'button', NULL, NULL, 'workflow:task:depute', NULL, 0, 0, 3);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3204, 3200, '审批历史', 'button', NULL, NULL, 'workflow:task:history', NULL, 0, 0, 4);

-- 3300 我的已办（二级菜单）
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3300, 3000, '我的已办', 'menu', '/workflow/task/completed', 'workflow/task/completed/index',
        'workflow:task:completed', 'i-ep-finished', 0, 0, 3);

-- 3400 我发起的（二级菜单）
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3400, 3000, '我发起的', 'menu', '/workflow/instance', 'workflow/instance/index',
        'workflow:instance:list', 'i-ep-sent', 0, 0, 4);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3401, 3400, '发起流程', 'button', NULL, NULL, 'workflow:instance:start', NULL, 0, 0, 1);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3402, 3400, '撤回', 'button', NULL, NULL, 'workflow:instance:revoke', NULL, 0, 0, 2);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3403, 3400, '终止', 'button', NULL, NULL, 'workflow:instance:terminate', NULL, 0, 0, 3);
INSERT INTO sys_menu (menu_id, parent_id, name, type, path, component, perms, icon, visible, status, sort_order)
VALUES (3404, 3400, '实例详情', 'button', NULL, NULL, 'workflow:instance:detail', NULL, 0, 0, 4);
