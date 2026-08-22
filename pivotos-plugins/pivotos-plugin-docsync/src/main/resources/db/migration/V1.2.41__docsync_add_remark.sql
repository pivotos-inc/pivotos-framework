-- =============================================================
-- PivotOS docsync 插件 · 新增备注字段
-- 为同步配置增加 remark 列，供管理员在后台填写备注说明
-- =============================================================

ALTER TABLE `doc_sync_config`
    ADD COLUMN `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `last_sync_result`;
