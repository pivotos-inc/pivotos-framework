-- =============================================================
-- PivotOS ${pluginName} 插件 · 菜单 SQL 模板（骨架占位，落笔前改名进 db/migration 并取全局下一版本号）
-- 用法：① 确定父菜单 ID（系统管理 1000 / 系统工具 1100 / AI 助手 3000…，或新建目录）；
--       ② id/按钮 id 取当前 sys_menu 未用号段（建议 3xxx 起递增）；
--       ③ perms 命名 ${pluginName}:<biz>:<action>；super_admin 走通配权限无需授权数据。
-- =============================================================

-- 目录（可选，一级菜单）：
-- INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
--     (3x00, 0, '${displayName}', 'M', '${pluginName}', NULL, '', 'menu', 9, 0, 0, 1, NOW(), 0);

-- 页面 + 按钮：
-- INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
--     (3x10, 3x00, '示例管理', 'C', 'example', '${pluginName}/example/index', '${pluginName}:example:list', 'list', 1, 0, 0, 1, NOW(), 0),
--     (3x11, 3x10, '示例新增', 'F', '', '', '${pluginName}:example:add',    '', 1, 0, 0, 1, NOW(), 0),
--     (3x12, 3x10, '示例修改', 'F', '', '', '${pluginName}:example:edit',   '', 2, 0, 0, 1, NOW(), 0),
--     (3x13, 3x10, '示例删除', 'F', '', '', '${pluginName}:example:remove', '', 3, 0, 0, 1, NOW(), 0);
