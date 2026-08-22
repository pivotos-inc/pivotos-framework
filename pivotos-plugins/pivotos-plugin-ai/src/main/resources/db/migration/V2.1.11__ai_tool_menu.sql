-- PivotOS ai 插件 · AI 工具管理菜单（S99 A2 首批业务工具批次）
-- 挂在 AI 助手(3000) 下：工具管理(3060) + 白名单/状态维护(3061) + 调用审计(3062)
-- 权限点与 AiToolController @SaCheckPermission 对齐（ai:tool:list / ai:tool:edit / ai:tool:invoke:list）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3060, 3000, '工具管理', 'C', 'tool', 'ai/tool/index', 'ai:tool:list', 'setting', 6, 0, 0, 1, NOW(), 0);

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3061, 3060, '工具白名单与状态维护', 'F', 'ai:tool:edit', 1, 0, 0, 1, NOW(), 0);

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3062, 3060, '工具调用审计查询', 'F', 'ai:tool:invoke:list', 2, 0, 0, 1, NOW(), 0);
