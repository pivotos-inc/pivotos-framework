-- =============================================================
-- PivotOS file 插件 · 文件管理菜单（固定 4000 号段字面 ID）
-- super_admin 走通配权限，无需 sys_role_menu 授权数据
-- 上传按钮权限 file:file:upload 仅控制前端按钮显隐
-- （presign/register 接口三端通用，只校验登录态，头像上传不受影响）
-- =============================================================

-- 一级目录：文件管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (4000, 0, '文件管理', 'M', '/file', '', '', 'folder', 50, 0, 0, 1, NOW(), 0);

-- 文件列表
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (4010, 4000, '文件列表', 'C', 'list', 'file/list/index', 'file:file:list', 'document', 1, 0, 0, 1, NOW(), 0),
    (4011, 4010, '文件查询', 'F', '', '', 'file:file:query',  '', 1, 0, 0, 1, NOW(), 0),
    (4012, 4010, '文件上传', 'F', '', '', 'file:file:upload', '', 2, 0, 0, 1, NOW(), 0),
    (4013, 4010, '文件删除', 'F', '', '', 'file:file:remove', '', 3, 0, 0, 1, NOW(), 0);
