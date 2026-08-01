-- =============================================================
-- V1.2.5：修正在线用户菜单记录（强制删除后重建，避免脏数据/软删除导致不可见）
-- =============================================================
DELETE FROM sys_menu WHERE id IN (1100, 1101);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1100, 1000, '在线用户', 'C', 'onlineUser', 'system/onlineUser/index', 'system:online-user:list', 'monitor', 'pc', 10, 0, 0, 1, NOW(), 0),
    (1101, 1100, '强退用户', 'F', '', '', 'system:online-user:kickout', '', 'pc', 1, 0, 0, 1, NOW(), 0);
