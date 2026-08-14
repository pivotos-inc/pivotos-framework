-- V1.2.27  补齐 flow_user 表缺失的 update_by 列
-- WarmFlow FlowUser 实体包含 updateBy 字段，V1.2.21 建表时遗漏。

ALTER TABLE flow_user ADD COLUMN update_by VARCHAR(64) NULL COMMENT '更新者' AFTER create_by;
