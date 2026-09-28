-- =============================================================
-- V2.2.1：多标签页布局持久化开关（sys.tagsview.persistEnabled）
-- true（默认）：PC 端标签页顺序 / 固定状态持久化到浏览器 localStorage，刷新后恢复
-- false：关闭持久化，每次进系统从空标签开始；系统工具-参数设置页可改
-- =============================================================

INSERT IGNORE INTO sys_config (id, config_name, config_key, config_value, config_type, remark, create_by, create_time, deleted)
VALUES (3, '标签页布局持久化开关', 'sys.tagsview.persistEnabled', 'true', 'Y', 'true 时 PC 端多标签页布局（顺序/固定状态）持久化到浏览器，刷新后恢复；false 关闭', 1, NOW(), 0);
