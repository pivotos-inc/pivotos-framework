-- =============================================================
-- PivotOS ai-coding 插件 · AI Coding 菜单（S37）
-- AI 助手（3000）下新增 AI Coding 页面入口；
-- 接口沿用登录校验（同 AI 对话页），不设按钮级权限。
-- super_admin 走通配权限，无需 sys_role_menu 授权数据。
-- =============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (3030, 3000, 'AI Coding', 'C', 'coding', 'ai/coding/index', '', 'ai', 3, 0, 0, 1, NOW(), 0);
