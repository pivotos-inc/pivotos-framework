-- =============================================================
-- V1.2.6：修复在线用户菜单 ID 冲突（1100 与"系统工具"目录冲突）
--
-- 根因：V1.2.4/1.2.5 使用 id=1100（与"系统工具"目录冲突），
-- 导致"参数设置"(parent_id=1100) 在"系统工具"被删后
-- 挂到"在线用户"(1100) 下。
--
-- 修复：
--  1. 1100 归还"系统工具"一级目录
--  2. 在线用户改用独立 ID：1095 菜单 + 1096 强退按钮
--     （紧跟 岗位管理 1094，语义连续）
-- =============================================================

-- 1. 清理冲突的在线用户旧记录（错误占用 1100）
DELETE FROM sys_menu WHERE menu_name = '在线用户' AND parent_id = 1000 AND id IN (1060, 1100);
DELETE FROM sys_menu WHERE menu_name = '强退用户' AND parent_id IN (1060, 1100) AND id IN (1061, 1101);

-- 2. 恢复被误删的"系统工具"一级目录（V1.2.5 误删了 1100）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (1100, 0, '系统工具', 'M', '/tool', '', '', 'tool', 20, 0, 0, 1, NOW(), 0)
ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
    path = VALUES(path), component = VALUES(component), perms = VALUES(perms), icon = VALUES(icon),
    sort = VALUES(sort), visible = VALUES(visible), status = VALUES(status);

-- 3. 在线用户使用独立 ID：1095（菜单）+ 1096（强退按钮）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1095, 1000, '在线用户', 'C', 'onlineUser', 'system/onlineUser/index', 'system:online-user:list', 'monitor', 'pc', 6, 0, 0, 1, NOW(), 0),
    (1096, 1095, '强退用户', 'F', '', '', 'system:online-user:kickout', '', 'pc', 1, 0, 0, 1, NOW(), 0)
ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id), menu_name = VALUES(menu_name), menu_type = VALUES(menu_type),
    device = VALUES(device), sort = VALUES(sort);
