-- =============================================================
-- PivotOS ai 插件 · AI 配置菜单（固定 3000 号段字面 ID，续 V1.1.5）
-- 管理页需 ai:provider:* 权限（super_admin 走通配）
-- =============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (3020, 3000, 'AI 配置', 'C', 'provider', 'ai/provider/index', 'ai:provider:list', 'setting', 2, 0, 0, 1, NOW(), 0),
    (3021, 3020, '配置查询', 'F', '', '', 'ai:provider:query',  '', 1, 0, 0, 1, NOW(), 0),
    (3022, 3020, '配置新增', 'F', '', '', 'ai:provider:add',    '', 2, 0, 0, 1, NOW(), 0),
    (3023, 3020, '配置修改', 'F', '', '', 'ai:provider:edit',   '', 3, 0, 0, 1, NOW(), 0),
    (3024, 3020, '配置删除', 'F', '', '', 'ai:provider:remove', '', 4, 0, 0, 1, NOW(), 0);
