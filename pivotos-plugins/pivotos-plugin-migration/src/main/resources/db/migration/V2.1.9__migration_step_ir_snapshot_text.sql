-- migration_step.ir_snapshot 由 JSON 类型改为 LONGTEXT
-- 原因：计划阶段存入的是步骤描述文本，非结构化 JSON；执行阶段的真实 IR 快照也可序列化为文本后存入。
ALTER TABLE `migration_step`
    MODIFY COLUMN `ir_snapshot` LONGTEXT DEFAULT NULL COMMENT '本步骤执行前的 IR 快照（文本/JSON）';
