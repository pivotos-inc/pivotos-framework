-- =============================================================
-- V1.2.36：S71 运营看板——「数据大屏」菜单（系统监控目录下）
-- 组件 monitor/bigscreen/index，权限 monitor:bigscreen:view
-- =============================================================
DELETE FROM sys_menu WHERE id IN (1230, 1231);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1230, 1200, '数据大屏', 'C', 'bigscreen', 'monitor/bigscreen/index', 'monitor:bigscreen:view', 'monitor', 'pc', 3, 0, 0, 1, NOW(), 0),
    (1231, 1230, '看板查询', 'F', '', '', 'monitor:dashboard:view', '', 'pc', 1, 0, 0, 1, NOW(), 0);
