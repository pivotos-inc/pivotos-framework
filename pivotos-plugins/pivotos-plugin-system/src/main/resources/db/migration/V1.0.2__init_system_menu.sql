-- =============================================================
-- PivotOS system 插件 · 菜单与按钮权限（固定 1000 号段字面 ID）
-- super_admin 走通配权限，无需 sys_role_menu 授权数据
-- =============================================================

-- 一级目录：系统管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (1000, 0, '系统管理', 'M', '/system', '', '', 'setting', 10, 0, 0, 1, NOW(), 0);

-- 用户管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (1010, 1000, '用户管理', 'C', 'user', 'system/user/index', 'system:user:list', 'user', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1011, 1010, '用户查询', 'F', '', '', 'system:user:query',     '', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1012, 1010, '用户新增', 'F', '', '', 'system:user:add',       '', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1013, 1010, '用户修改', 'F', '', '', 'system:user:edit',      '', 3, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1014, 1010, '用户删除', 'F', '', '', 'system:user:remove',    '', 4, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1015, 1010, '重置密码', 'F', '', '', 'system:user:resetPwd',  '', 5, 0, 0, 1, NOW(), 0);

-- 角色管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (1020, 1000, '角色管理', 'C', 'role', 'system/role/index', 'system:role:list', 'peoples', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1021, 1020, '角色查询', 'F', '', '', 'system:role:query',   '', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1022, 1020, '角色新增', 'F', '', '', 'system:role:add',     '', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1023, 1020, '角色修改', 'F', '', '', 'system:role:edit',    '', 3, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1024, 1020, '角色删除', 'F', '', '', 'system:role:remove',  '', 4, 0, 0, 1, NOW(), 0);

-- 菜单管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (1030, 1000, '菜单管理', 'C', 'menu', 'system/menu/index', 'system:menu:list', 'tree-table', 3, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1031, 1030, '菜单查询', 'F', '', '', 'system:menu:query',   '', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1032, 1030, '菜单新增', 'F', '', '', 'system:menu:add',     '', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1033, 1030, '菜单修改', 'F', '', '', 'system:menu:edit',    '', 3, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1034, 1030, '菜单删除', 'F', '', '', 'system:menu:remove',  '', 4, 0, 0, 1, NOW(), 0);

-- 部门管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (1040, 1000, '部门管理', 'C', 'dept', 'system/dept/index', 'system:dept:list', 'tree', 4, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1041, 1040, '部门查询', 'F', '', '', 'system:dept:query',   '', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1042, 1040, '部门新增', 'F', '', '', 'system:dept:add',     '', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1043, 1040, '部门修改', 'F', '', '', 'system:dept:edit',    '', 3, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1044, 1040, '部门删除', 'F', '', '', 'system:dept:remove',  '', 4, 0, 0, 1, NOW(), 0);

-- 字典管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (1050, 1000, '字典管理', 'C', 'dict', 'system/dict/index', 'system:dict:list', 'dict', 5, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1051, 1050, '字典查询', 'F', '', '', 'system:dict:query',   '', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1052, 1050, '字典新增', 'F', '', '', 'system:dict:add',     '', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1053, 1050, '字典修改', 'F', '', '', 'system:dict:edit',    '', 3, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1054, 1050, '字典删除', 'F', '', '', 'system:dict:remove',  '', 4, 0, 0, 1, NOW(), 0);

-- 一级目录：系统工具 + 参数设置
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (1100, 0, '系统工具', 'M', '/tool', '', '', 'tool', 20, 0, 0, 1, NOW(), 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
                                                                                                                                                     (1110, 1100, '参数设置', 'C', 'config', 'system/config/index', 'system:config:list', 'edit', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1111, 1110, '参数查询', 'F', '', '', 'system:config:query',   '', 1, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1112, 1110, '参数新增', 'F', '', '', 'system:config:add',     '', 2, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1113, 1110, '参数修改', 'F', '', '', 'system:config:edit',    '', 3, 0, 0, 1, NOW(), 0),
                                                                                                                                                     (1114, 1110, '参数删除', 'F', '', '', 'system:config:remove',  '', 4, 0, 0, 1, NOW(), 0);
