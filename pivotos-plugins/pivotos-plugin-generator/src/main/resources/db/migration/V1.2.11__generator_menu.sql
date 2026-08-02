-- =============================================================
-- PivotOS generator 插件 · 代码生成器菜单（S31 遗漏补齐，S37）
-- 背景：V1.2.9 只建了 gen_table/gen_table_column 两表，未插菜单，
--       PC 端菜单驱动动态路由，生成器页（system/generator/index）无法从侧边栏进入。
-- super_admin 走通配权限，无需 sys_role_menu 授权数据。
-- =============================================================

-- 代码生成（系统工具 1100 下，参数设置 1110 之后）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1120, 1100, '代码生成', 'C', 'generator', 'system/generator/index', 'generator:gen:list', 'tool', 2, 0, 0, 1, NOW(), 0),
    (1121, 1120, '导入表',   'F', '', '', 'generator:gen:import',   '', 1, 0, 0, 1, NOW(), 0),
    (1122, 1120, '预览代码', 'F', '', '', 'generator:gen:preview',  '', 2, 0, 0, 1, NOW(), 0),
    (1123, 1120, '生成代码', 'F', '', '', 'generator:gen:generate', '', 3, 0, 0, 1, NOW(), 0),
    (1124, 1120, '同步表结构','F', '', '', 'generator:gen:synch',    '', 4, 0, 0, 1, NOW(), 0),
    (1125, 1120, '删除表',   'F', '', '', 'generator:gen:remove',   '', 5, 0, 0, 1, NOW(), 0);
