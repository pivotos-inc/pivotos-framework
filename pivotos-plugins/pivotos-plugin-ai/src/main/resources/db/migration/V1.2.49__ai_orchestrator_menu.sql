-- =============================================================
-- PivotOS ai 插件 · AI 工具编排页入口（A5-1 / S116）
-- 背景：A2 工具体系（S98/S112）已把工具面（注册 / 白名单 / 二次确认 / 审计）
--       与 REST endpoints 建好，但多步编排需要一个人能「看计划 → 确认 → 执行 → 看结果」的入口，
--       否则只能靠 curl 串三个接口，不成产品形态。
--
-- 口径选择（与 S112 评审页同口径）：
--   · 挂在「AI 助手」目录（3000）下，不挂在工具管理页（3060）下——
--     vue-router 中非 Layout 父级承载子路由会渲染到父组件 router-view，
--     而 ai/tool/index.vue 是终端页面（无 router-view），挂其下会空白。
--   · visible=0：编排是独立业务面，侧边栏应可见（不同于评审页的跳转承载形态）。
--   · 权限不复用 ai:tool:*：编排能一次连续触发多个写工具，权限面必须比
--     单工具调用更窄，故另开 ai:orchestrator:list（记录查看） / ai:orchestrator:run（计划与执行）。
--   · 按钮行（F）只登记权限码、不渲染路由，供角色授权面板勾选（同 ai:coding 系列口径）。
-- =============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (3070, 3000, 'AI 工具编排',       'C', 'orchestrator', 'ai/orchestrator/index', 'ai:orchestrator:list', 'tree', 'pc', 8, 0, 0, 1, NOW(), 0),
    (3071, 3070, '编排计划生成与执行', 'F', '',            '',                      'ai:orchestrator:run',  '',     'pc', 1, 0, 0, 1, NOW(), 0),
    (3072, 3070, '编排记录查询',       'F', '',            '',                      'ai:orchestrator:list', '',     'pc', 2, 0, 0, 1, NOW(), 0);
