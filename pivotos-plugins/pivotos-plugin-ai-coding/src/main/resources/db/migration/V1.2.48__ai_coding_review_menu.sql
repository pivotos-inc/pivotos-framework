-- =============================================================
-- PivotOS ai-coding 插件 · AI Coding 评审页入口（A4-3 / S112）
-- 背景：A4-2（S111）已把「定位结论 / 结构化 edit / diff / 门禁结果」四段产物
--       入库 sys_coding_session，但只有 JSON 可看——评审必须人工看 diff，
--       靠读库或调接口不成产品形态。本迁移登记评审页路由，使会话有独立可分享 URL。
--
-- 口径选择（简报已说明）：
--   · 挂在「AI 助手」目录（3000）下而非 AI Coding 页（3030）下——vue-router 中
--     非 Layout 父级承载子路由会在父组件 router-view 渲染，而 ai/coding/index.vue
--     为终端页面（无 router-view），挂 3030 下评审页会渲染成空白。
--     故 path 写作 'coding/review'（相对 3000 → /ai/coding/review），
--     仍属 AI Coding 同一入口域，不另起顶层路由。
--   · visible=1（隐藏）：列表页「查看评审」按钮跳转进入，不占侧边栏位置。
--   · perms 复用既有 ai:coding:list（详情页同一权限），不新增权限码。
--   · 评审页的「通过」动作为既有 POST /ai-coding/session/{id}/apply，权限 ai:coding:apply。
-- =============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted) VALUES
    (3034, 3000, 'AI Coding 评审', 'C', 'coding/review', 'ai/coding/review', 'ai:coding:list', 'document', 'pc', 7, 1, 0, 1, NOW(), 0);
