-- migration_task.analysis_report / migration_plan 由 JSON 类型改为 LONGTEXT
-- 原因：AI 生成的分析报告与迁移计划为自然语言/Markdown，非结构化 JSON，存 LONGTEXT 更合适。
ALTER TABLE `migration_task`
    MODIFY COLUMN `analysis_report` LONGTEXT COMMENT '架构分析报告（AI 生成，Markdown 文本）',
    MODIFY COLUMN `migration_plan`  LONGTEXT COMMENT '迁移计划（AI 生成，Markdown 文本）';
