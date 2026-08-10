-- =============================================================
-- PivotOS system 插件 · 岗位管理 (2.1-F3)
-- sys_user 增加 post_id 字段 + 岗位管理菜单与按钮权限
-- =============================================================

-- 1. sys_user 增加岗位关联字段
ALTER TABLE sys_user ADD COLUMN post_id BIGINT DEFAULT NULL COMMENT '岗位ID' AFTER dept_id;
CREATE INDEX idx_user_post_id ON sys_user (post_id);

-- 2. 岗位管理菜单（系统管理 1000 下，续 sort 9）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1090, 1000, '岗位管理', 'C', 'post', 'system/post/index', 'system:post:list', 'guide', 9, 0, 0, 1, NOW(), 0),
    (1091, 1090, '岗位查询', 'F', '', '', 'system:post:query',   '', 1, 0, 0, 1, NOW(), 0),
    (1092, 1090, '岗位新增', 'F', '', '', 'system:post:add',     '', 2, 0, 0, 1, NOW(), 0),
    (1093, 1090, '岗位修改', 'F', '', '', 'system:post:edit',    '', 3, 0, 0, 1, NOW(), 0),
    (1094, 1090, '岗位删除', 'F', '', '', 'system:post:remove',  '', 4, 0, 0, 1, NOW(), 0);
