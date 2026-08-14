-- V1.2.25  补齐 WarmFlow 1.8.7 实体所需的审计列
-- WarmFlow ORM 实体 (FlowDefinition/FlowNode/FlowSkip/FlowTask/FlowHisTask)
-- 包含 createBy、updateBy 字段，但 V1.2.21 建表时未包含这些列，
-- 导致 MyBatis-Plus 生成 SELECT/INSERT 时报 Unknown column 'create_by'。

-- 1. flow_definition: 添加 create_by + update_by
ALTER TABLE flow_definition ADD COLUMN create_by VARCHAR(64) NULL COMMENT '创建者' AFTER ext;
ALTER TABLE flow_definition ADD COLUMN update_by VARCHAR(64) NULL COMMENT '更新者' AFTER create_by;

-- 2. flow_node: 添加 create_by + update_by
ALTER TABLE flow_node ADD COLUMN create_by VARCHAR(64) NULL COMMENT '创建者' AFTER ext;
ALTER TABLE flow_node ADD COLUMN update_by VARCHAR(64) NULL COMMENT '更新者' AFTER create_by;

-- 3. flow_skip: 添加 create_by + update_by
ALTER TABLE flow_skip ADD COLUMN create_by VARCHAR(64) NULL COMMENT '创建者' AFTER coordinate;
ALTER TABLE flow_skip ADD COLUMN update_by VARCHAR(64) NULL COMMENT '更新者' AFTER create_by;

-- 4. flow_instance: 仅添加 update_by（create_by 已在 V1.2.21 中）
ALTER TABLE flow_instance ADD COLUMN update_by VARCHAR(64) NULL COMMENT '更新者' AFTER create_by;

-- 5. flow_task: 添加 create_by + update_by
ALTER TABLE flow_task ADD COLUMN create_by VARCHAR(64) NULL COMMENT '创建者' AFTER form_path;
ALTER TABLE flow_task ADD COLUMN update_by VARCHAR(64) NULL COMMENT '更新者' AFTER create_by;

-- 6. flow_his_task: 添加 create_by + update_by
ALTER TABLE flow_his_task ADD COLUMN create_by VARCHAR(64) NULL COMMENT '创建者' AFTER ext;
ALTER TABLE flow_his_task ADD COLUMN update_by VARCHAR(64) NULL COMMENT '更新者' AFTER create_by;
