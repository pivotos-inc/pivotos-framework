-- =============================================================
-- PivotOS ai 插件 · 菜单（固定 3000 号段字面 ID）
-- super_admin 走通配权限，无需 sys_role_menu 授权数据
-- "AI 对话"为个人页，登录即可见（perms 留空，接口只校验登录态）
-- =============================================================

-- 一级目录：AI 助手
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3000, 0, 'AI 助手', 'M', '/ai', '', '', 'ai', 40, 0, 0, 1, NOW(), 0);

-- AI 对话（个人页，登录即可见）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3010, 3000, 'AI 对话', 'C', 'chat', 'ai/chat/index', '', 'chat-dot', 1, 0, 0, 1, NOW(), 0);
