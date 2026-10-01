-- =============================================================
-- V1.2.52：FE-COMP 三件套演示页入口（S123 · 虚拟表格 / 动态表单 / 导入预览）
--
-- 口径要点（勿踩）：
--   ① 迁移号：**V1.2.52**（V1.2.50 = S127-B ES 监控、V1.2.51 = S130 数据监控，本脚本取下一可用号）；
--   ② 菜单 id：1180 ~ 1183 —— 1100 系统工具目录下的现存子节点是 1110/1120/1140/1150，
--      **1160/1167x 已被租户管理（1160/1161…）与租户套餐（1170/1171…）占用**（它们挂在 1000 下，
--      但 id 是全局唯一的），1180~1189 全区空闲，故目录取 1180、三页取 1181~1183，不按 1160 顺延；
--   ③ 挂载点：挂「系统工具」（1100）而非「系统管理」（1000）——三页是前端能力演示，不是系统配置面；
--      目录节点（M）走 Layout，子节点才是终端页组件；
--   ④ 权限：三页共用一个查看码 `tool:demo:list`（查看演示不需要更细颗粒），不新增写类权限码——
--      「导入预览」页默认只预览不落库，真落库时走既有 `system:user:import`。
-- =============================================================

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (1180, 1100, '前端组件演示', 'M', 'demo',            '',                                  '',               'tool',     'pc', 10, 0, 0, 1, NOW(), 0),
    (1181, 1180, '虚拟表格',     'C', 'virtual-table',   'tool/demo/virtual-table/index',   'tool:demo:list', 'grid',     'pc', 1,  0, 0, 1, NOW(), 0),
    (1182, 1180, '动态表单',     'C', 'schema-form',     'tool/demo/schema-form/index',     'tool:demo:list', 'edit-pen', 'pc', 2,  0, 0, 1, NOW(), 0),
    (1183, 1180, '导入预览',     'C', 'import-preview',  'tool/demo/import-preview/index',  'tool:demo:list', 'upload',   'pc', 3,  0, 0, 1, NOW(), 0);
