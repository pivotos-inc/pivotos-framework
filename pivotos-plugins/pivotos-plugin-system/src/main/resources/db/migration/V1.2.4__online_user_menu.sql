-- =============================================================
-- PivotOS system 插件 · 在线用户管理 (2.1-F4)
-- 在线用户菜单与按钮权限（无表结构，数据源自 Sa-Token 会话）
-- =============================================================
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1100, 1000, '在线用户', 'C', 'onlineUser', 'system/onlineUser/index', 'system:online-user:list', 'monitor', 10, 0, 0, 1, NOW(), 0),
    (1101, 1100, '强退用户', 'F', '', '', 'system:online-user:kickout', '', 1, 0, 0, 1, NOW(), 0);
