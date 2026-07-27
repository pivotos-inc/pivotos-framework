-- =============================================================
-- PivotOS message 插件 · 菜单与按钮权限（固定 2000 号段字面 ID）
-- super_admin 走通配权限，无需 sys_role_menu 授权数据
-- “我的消息”为个人页，登录即可见（perms 留空，接口用 @SaCheckLogin）
-- =============================================================

-- 一级目录：消息中心
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (2000, 0, '消息中心', 'M', '/message', '', '', 'message', 30, 0, 0, 1, NOW(), 0);

-- 消息模板
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (2010, 2000, '消息模板', 'C', 'template', 'message/template/index', 'message:template:list', 'edit', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (2011, 2010, '模板查询', 'F', '', '', 'message:template:query',  '', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (2012, 2010, '模板新增', 'F', '', '', 'message:template:add',    '', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (2013, 2010, '模板修改', 'F', '', '', 'message:template:edit',   '', 3, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (2014, 2010, '模板删除', 'F', '', '', 'message:template:remove', '', 4, 0, 0, 1, NOW(), 0);

-- 消息管理（后台发送 + 全量分页）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (2020, 2000, '消息管理', 'C', 'manage', 'message/manage/index', 'message:message:list', 'list', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (2021, 2020, '消息查询', 'F', '', '', 'message:message:query', '', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (2022, 2020, '消息发送', 'F', '', '', 'message:message:send',  '', 2, 0, 0, 1, NOW(), 0);

-- 我的消息（个人页，登录即可见）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (2030, 2000, '我的消息', 'C', 'user', 'message/user/index', '', 'bell', 3, 0, 0, 1, NOW(), 0);
