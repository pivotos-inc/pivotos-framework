-- V1.2.51：通用数据监控菜单（DB / ES / Redis）
-- 注：① 1.2.50 已由 S127-B 占用（ES 监控），本脚本取下一可用号 1.2.51；
--     ② 菜单 id 取 1250 —— 1200 系统监控目录 / 1210 服务监控 / 1220 缓存监控 / 1230 数据大屏 / 1240 ES 监控 均已占用，勿顺延；
--     ③ 权限只控制「能不能点按钮」，SQL 安全闸门在执行侧硬生效（超管也不豁免），二者不可互相替代。
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1250, 1200, '数据监控', 'C', 'data', 'monitor/data/index', 'monitor:data:list', 'coin', 'pc', 5, 0, 0, 1, NOW(), 0);

-- 按钮：预览（分页查看表数据）与自由 SQL 执行（高危，默认只给超管）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1251, 1250, '数据预览',     'F', 'monitor:data:preview', 'pc', 1, 0, 0, 1, NOW(), 0),
    (1252, 1250, '自由SQL执行',  'F', 'monitor:data:query',   'pc', 2, 0, 0, 1, NOW(), 0);
