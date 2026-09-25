-- =============================================================
-- V2.2.2：标签页激活自动刷新开关（sys.tagsview.refreshOnActivate）
-- true（默认）：切换回标签页时自动重拉当前页数据（分页/查询条件保留）
-- false：关闭自动刷新，页面数据保持缓存不动；系统工具-参数设置页可改
-- =============================================================

INSERT IGNORE INTO sys_config (id, config_name, config_key, config_value, config_type, remark, create_by, create_time, deleted)
VALUES (4, '标签页激活自动刷新开关', 'sys.tagsview.refreshOnActivate', 'true', 'Y', 'true 时切换回标签页自动重拉当前页数据（分页/查询条件保留）；false 关闭', 1, NOW(), 0);
