-- =============================================================
-- V1.1.3：sys_menu 增加 device 可见端字段（S16 移动端工作台）
-- 取值：pc / app / mini 逗号分隔多值；存量行默认 'pc'，PC 行为不变
-- =============================================================

ALTER TABLE sys_menu
    ADD COLUMN device VARCHAR(20) NOT NULL DEFAULT 'pc' COMMENT '可见端（pc/app/mini 逗号分隔）' AFTER icon;

-- 移动端工作台演示宫格：消息中心（PC 菜单体系的菜单管理里可继续维护，device=app,mini 即下发移动端）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, device, sort, visible, status, create_time)
SELECT 110000000000000001, 0, '消息中心', 'C', '/pages/notice/notice', '', '', 'bell', 'app,mini', 1, 0, 0, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 110000000000000001);
