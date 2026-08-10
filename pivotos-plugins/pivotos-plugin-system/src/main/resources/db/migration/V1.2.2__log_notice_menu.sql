-- =============================================================
-- PivotOS system 插件 · 日志管理 + 通知公告菜单（续 1000 号段字面 ID）
-- super_admin 走通配权限，无需 sys_role_menu 授权数据
-- =============================================================

-- 登录日志（系统管理 1000 下，续 sort 6）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1060, 1000, '登录日志', 'C', 'log/login', 'system/log/login/index', 'system:loginlog:list', 'document', 6, 0, 0, 1, NOW(), 0),
    (1061, 1060, '登录日志查询', 'F', '', '', 'system:loginlog:query', '', 1, 0, 0, 1, NOW(), 0);

-- 操作日志
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1070, 1000, '操作日志', 'C', 'log/oper', 'system/log/oper/index', 'system:operlog:list', 'list', 7, 0, 0, 1, NOW(), 0),
    (1071, 1070, '操作日志查询', 'F', '', '', 'system:operlog:query', '', 1, 0, 0, 1, NOW(), 0);

-- 通知公告（管理页；已发布公告读取走 @SaCheckLogin，登录即可读）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1080, 1000, '通知公告', 'C', 'notice', 'system/notice/index', 'system:notice:list', 'bell', 8, 0, 0, 1, NOW(), 0),
    (1081, 1080, '公告查询', 'F', '', '', 'system:notice:query',   '', 1, 0, 0, 1, NOW(), 0),
    (1082, 1080, '公告新增', 'F', '', '', 'system:notice:add',     '', 2, 0, 0, 1, NOW(), 0),
    (1083, 1080, '公告修改', 'F', '', '', 'system:notice:edit',    '', 3, 0, 0, 1, NOW(), 0),
    (1084, 1080, '公告删除', 'F', '', '', 'system:notice:remove',  '', 4, 0, 0, 1, NOW(), 0),
    (1085, 1080, '公告发布', 'F', '', '', 'system:notice:publish', '', 5, 0, 0, 1, NOW(), 0);
