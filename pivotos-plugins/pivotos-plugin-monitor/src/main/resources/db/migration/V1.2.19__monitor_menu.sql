-- =============================================================
-- PivotOS monitor 插件 · 监控菜单 (S48 2.3-F7)
-- 「系统监控」一级目录 + 服务监控/缓存监控菜单（无表结构，数据源自 oshi/JMX/Redis INFO）
-- 注：版本号取 1.2.19 —— 1.2.18 曾被 S47 演示脚本占用（开发库残留已清理），
--     跳号避让幽灵版本，防止其他环境已应用旧脚本时 checksum 冲突
-- =============================================================
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1200, 0, '系统监控', 'M', '/monitor', '', '', 'monitor', 'pc', 15, 0, 0, 1, NOW(), 0),
    (1210, 1200, '服务监控', 'C', 'server', 'monitor/server/index', 'monitor:server:list', 'cpu', 'pc', 1, 0, 0, 1, NOW(), 0),
    (1220, 1200, '缓存监控', 'C', 'cache', 'monitor/cache/index', 'monitor:cache:list', 'histogram', 'pc', 2, 0, 0, 1, NOW(), 0);
