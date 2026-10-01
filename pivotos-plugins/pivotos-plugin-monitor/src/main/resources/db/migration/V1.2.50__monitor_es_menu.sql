-- =============================================================
-- PivotOS monitor 插件 · ES 监控菜单（挂在 1200「系统监控」目录下）
-- 数据源：GET /monitor/es（集群健康/节点/索引/JVM/分片 + 当前生效搜索实现与回落状态）
-- 注：① 1.2.49 已由 S116 占用（AI 工具编排菜单），本脚本取下一可用号 1.2.50；
--     ② 菜单 id 取 1240 —— 1210 服务监控 / 1220 缓存监控 / 1230 数据大屏 均已占用
--        （1230 被「数据大屏」占了，别按 1210/1220 的顺序惯性往下写）
-- =============================================================
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1240, 1200, 'ES 监控', 'C', 'es', 'monitor/es/index', 'monitor:es:list', 'search', 'pc', 4, 0, 0, 1, NOW(), 0);
