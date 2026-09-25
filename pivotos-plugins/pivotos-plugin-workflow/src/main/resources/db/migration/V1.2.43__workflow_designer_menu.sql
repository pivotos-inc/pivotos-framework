-- =============================================================
-- V1.2.43：S103 C4 bpmn-js 流程设计器——新版设计器菜单
-- 并存灰度口径：旧内置 jar 设计器入口（流程定义页「设计」按钮 iframe）保留，
-- 新设计器独立菜单页（bpmn-js 画布），v2.14.0 再评估下线旧入口。
-- =============================================================

-- 3560 流程设计器（二级菜单，挂 3500 流程管理下）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_by, create_time, deleted)
VALUES (3560, 3500, '流程设计器', 'C', 'designer', 'workflow/designer/index', 'workflow:designer:view', 'edit', 'pc', 2, 0, 0, 1, NOW(), 0);

-- 3561 保存定义按钮（发布复用既有 workflow:definition:publish 权限点）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, device, sort, visible, status, create_by, create_time, deleted)
VALUES (3561, 3560, '保存定义', 'F', 'workflow:designer:save', 'pc', 1, 0, 0, 1, NOW(), 0);
