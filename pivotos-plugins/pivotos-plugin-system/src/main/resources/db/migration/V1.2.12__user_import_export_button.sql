-- =============================================================
-- PivotOS system 插件 · 用户导入/导出按钮权限（S27 遗漏补齐，S37）
-- 背景：S27 用户管理页接入了 Excel 导入/导出，后端校验
--       system:user:import / system:user:export，但菜单表未登记按钮，
--       非超管角色在角色管理页无法勾选这两项权限。
-- super_admin 走通配权限，无需 sys_role_menu 授权数据。
-- =============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1016, 1010, '用户导入', 'F', '', '', 'system:user:import', '', 6, 0, 0, 1, NOW(), 0),
    (1017, 1010, '用户导出', 'F', '', '', 'system:user:export', '', 7, 0, 0, 1, NOW(), 0);
