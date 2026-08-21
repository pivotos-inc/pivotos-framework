-- =============================================================
-- PivotOS 旧系统迁移插件 · 产物表 step_id 允许为空
-- 解析阶段产生的任务级产物尚未关联具体步骤。
-- =============================================================

ALTER TABLE `migration_artifact`
    MODIFY COLUMN `step_id` BIGINT(20) DEFAULT NULL COMMENT '所属步骤（任务级产物可为 NULL）';
