-- =============================================================
-- PivotOS system 插件 · 定时任务菜单重构（S89-2）
-- 将定时任务从「系统工具(1100)」下拆出为独立顶级菜单(1300)，
-- 并预留统计报表子菜单(1330, 隐藏+停用)。
-- 注：1200-1231 范围已被「系统监控」菜单占用，使用 1300 范围。
-- =============================================================

-- 1. 创建独立顶级菜单：定时任务
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (1300, 0, '定时任务', 'M', '/job', '', '', 'timer', 15, 0, 0, 1, NOW(), 0);

-- 2. 任务管理（从 1133 迁移到独立菜单下，perms 保持 system:job:* 不变）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1310, 1300, '任务管理',     'C', 'task', 'system/job/index', 'system:job:list',          '',        1, 0, 0, 1, NOW(), 0),
    (1311, 1310, '任务查询',     'F', '',     '',                  'system:job:query',        '',        1, 0, 0, 1, NOW(), 0),
    (1312, 1310, '任务新增',     'F', '',     '',                  'system:job:add',          '',        2, 0, 0, 1, NOW(), 0),
    (1313, 1310, '任务修改',     'F', '',     '',                  'system:job:edit',         '',        3, 0, 0, 1, NOW(), 0),
    (1314, 1310, '任务删除',     'F', '',     '',                  'system:job:remove',       '',        4, 0, 0, 1, NOW(), 0),
    (1315, 1310, '手动触发',     'F', '',     '',                  'system:job:trigger',      '',        5, 0, 0, 1, NOW(), 0),
    (1316, 1310, '启停切换',     'F', '',     '',                  'system:job:changeStatus', '',        6, 0, 0, 1, NOW(), 0);

-- 3. 执行记录从系统工具(1100)移到定时任务(1300)下
-- 1130 原始定义（V1.2.15）: path='jobLog', component='system/jobLog/index', perms='system:joblog:list'
UPDATE sys_menu SET parent_id = 1300, sort = 2 WHERE id = 1130;

-- 4. 删除旧定时任务菜单（1133-1139 在系统工具下）
DELETE FROM sys_menu WHERE id IN (1133, 1134, 1135, 1136, 1137, 1138, 1139);

-- 5. 预留统计报表（隐藏 + 停用，后续功能开发后启用）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (1330, 1300, '统计报表', 'C', 'report', 'job/report/index', 'system:job:report', 'chart', 3, 1, 1, 1, NOW(), 0);
