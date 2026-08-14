-- ================================================================
-- S57 · 审批域子菜单（2.5-F3 审批流转）
-- ================================================================

-- 流程管理（id=3500）由 V1.2.21 创建
-- 流程定义（id=3510~3515）由 V1.2.22 创建
-- 本脚本追加审批域子菜单：我的待办 / 我的已办 / 我发起的

-- 3520 我的待办（二级菜单）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3520, 3500, '我的待办', 'C', 'task/pending', 'workflow/task/pending/index',
        'workflow:task:pending', 'edit-pen', 2, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3521, 3520, '审批操作', 'F', 'workflow:task:approve', 1, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3522, 3520, '转办', 'F', 'workflow:task:transfer', 2, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3523, 3520, '委派', 'F', 'workflow:task:depute', 3, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3524, 3520, '审批历史', 'F', 'workflow:task:history', 4, 0, 0, 1, NOW(), 0);

-- 3530 我的已办（二级菜单）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3530, 3500, '我的已办', 'C', 'task/completed', 'workflow/task/completed/index',
        'workflow:task:completed', 'finished', 3, 0, 0, 1, NOW(), 0);

-- 3540 我发起的（二级菜单）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3540, 3500, '我发起的', 'C', 'instance', 'workflow/instance/started/index',
        'workflow:instance:list', 'promotion', 4, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3541, 3540, '发起流程', 'F', 'workflow:instance:start', 1, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3542, 3540, '撤回', 'F', 'workflow:instance:revoke', 2, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3543, 3540, '终止', 'F', 'workflow:instance:terminate', 3, 0, 0, 1, NOW(), 0);
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3544, 3540, '实例详情', 'F', 'workflow:instance:detail', 4, 0, 0, 1, NOW(), 0);
