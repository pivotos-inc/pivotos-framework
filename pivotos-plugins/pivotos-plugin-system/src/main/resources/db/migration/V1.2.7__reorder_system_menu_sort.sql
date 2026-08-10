-- ============================================
-- V1.2.7: 重新调整系统管理子菜单排序
-- 按业务语义分组，使菜单层级更合理
-- ============================================

-- 用户与权限 (sort 1-3)
UPDATE sys_menu SET sort = 1 WHERE id = 1010 AND menu_name = '用户管理';
UPDATE sys_menu SET sort = 2 WHERE id = 1020 AND menu_name = '角色管理';
UPDATE sys_menu SET sort = 3 WHERE id = 1030 AND menu_name = '菜单管理';

-- 组织架构 (sort 4-5): 岗位管理移到部门管理旁边
UPDATE sys_menu SET sort = 4 WHERE id = 1040 AND menu_name = '部门管理';
UPDATE sys_menu SET sort = 5 WHERE id = 1090 AND menu_name = '岗位管理';

-- 基础数据 (sort 6)
UPDATE sys_menu SET sort = 6 WHERE id = 1050 AND menu_name = '字典管理';

-- 系统监控 (sort 7-9)
UPDATE sys_menu SET sort = 7 WHERE id = 1095 AND menu_name = '在线用户';
UPDATE sys_menu SET sort = 8 WHERE id = 1060 AND menu_name = '登录日志';
UPDATE sys_menu SET sort = 9 WHERE id = 1070 AND menu_name = '操作日志';

-- 消息通知 (sort 10)
UPDATE sys_menu SET sort = 10 WHERE id = 1080 AND menu_name = '通知公告';
