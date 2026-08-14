-- V1.2.26  修复 WarmFlow node_type 列类型（BIT(1) → TINYINT）
-- V1.2.21 建表时 node_type 定义为 BIT(1)，但 WarmFlow 实际使用 0-4：
-- 0=开始 1=中间(用户任务) 2=结束 3=互斥网关 4=并行网关
-- BIT(1) 只能存 0/1，导致保存结束节点(2)和网关节点时报 Data truncation。

ALTER TABLE flow_node MODIFY COLUMN node_type TINYINT NOT NULL COMMENT '节点类型（0开始 1中间 2结束 3互斥网关 4并行网关）';
ALTER TABLE flow_skip MODIFY COLUMN now_node_type TINYINT NULL COMMENT '当前节点类型';
ALTER TABLE flow_skip MODIFY COLUMN next_node_type TINYINT NULL COMMENT '下一个节点类型';
ALTER TABLE flow_instance MODIFY COLUMN node_type TINYINT NOT NULL COMMENT '节点类型';
ALTER TABLE flow_task MODIFY COLUMN node_type TINYINT NOT NULL COMMENT '节点类型';
ALTER TABLE flow_his_task MODIFY COLUMN node_type TINYINT NULL COMMENT '节点类型';
