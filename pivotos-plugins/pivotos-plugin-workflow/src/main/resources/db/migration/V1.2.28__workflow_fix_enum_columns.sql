-- V1.2.28  修复 WarmFlow 枚举列类型（BIT(1) → TINYINT）
-- is_publish 取值 0/1/9，activity_status 取值 0/1，
-- cooperate_type 取值 1-7，BIT(1) 只能存 0/1。

ALTER TABLE flow_definition MODIFY COLUMN is_publish TINYINT NOT NULL DEFAULT 0 COMMENT '是否发布（0未发布 1已发布 9失效）';
ALTER TABLE flow_definition MODIFY COLUMN activity_status TINYINT NOT NULL DEFAULT 1 COMMENT '流程激活状态（0挂起 1激活）';
ALTER TABLE flow_instance MODIFY COLUMN activity_status TINYINT NOT NULL DEFAULT 1 COMMENT '流程激活状态（0挂起 1激活）';
ALTER TABLE flow_his_task MODIFY COLUMN cooperate_type TINYINT NOT NULL DEFAULT 0 COMMENT '协作方式(1审批 2转办 3委派 4会签 5票签 6加签 7减签)';
