-- =============================================================
-- PivotOS ai-coding 插件 · AI Coding 按钮级权限（S41）
-- 背景：V1.2.13 只建了菜单 3030（perms 为空、无按钮），S40 鉴权修复
--       仅做到类级登录门禁，未做按钮级权限。
-- 本迁移：菜单 3030 补 perms=ai:coding:list，新增 3 个 F 按钮
--       （生成代码/确认生成/会话查询），Controller 对应端点补
--       @SaCheckPermission；super_admin 走通配权限，无需授权数据。
-- 会话行级隔离（create_by）为代码侧变更，不涉及 DDL。
-- =============================================================

UPDATE sys_menu SET perms = 'ai:coding:list', update_time = NOW() WHERE id = 3030;

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (3031, 3030, '生成代码',   'F', '', '', 'ai:coding:parse', '', 1, 0, 0, 1, NOW(), 0),
    (3032, 3030, '确认生成',   'F', '', '', 'ai:coding:apply', '', 2, 0, 0, 1, NOW(), 0),
    (3033, 3030, '会话查询',   'F', '', '', 'ai:coding:list',  '', 3, 0, 0, 1, NOW(), 0);
