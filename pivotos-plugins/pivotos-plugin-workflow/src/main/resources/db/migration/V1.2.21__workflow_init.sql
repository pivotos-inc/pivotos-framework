-- V1.2.21  WarmFlow 工作流引擎核心表 + 流程管理一级菜单
-- 引擎: org.dromara.warm:warm-flow-mybatis-plus-sb4-starter:1.8.7
-- 表前缀: flow_ (ArchUnit A8 已登记 pivotos-plugin-workflow -> flow_)
-- 注意: WarmFlow 表不继承 BaseDO，引擎自管理 del_flag / tenant_id

-- ========== 1. flow_definition 流程定义表 ==========
CREATE TABLE IF NOT EXISTS flow_definition (
    id           BIGINT        NOT NULL COMMENT '主键id',
    flow_code    VARCHAR(40)   NOT NULL COMMENT '流程编码',
    flow_name    VARCHAR(100)  NOT NULL COMMENT '流程名称',
    model_value  VARCHAR(40)   NOT NULL DEFAULT 'CLASSICS' COMMENT '设计器模型（CLASSICS经典 MIMIC仿钉钉）',
    category     VARCHAR(100)  NULL     COMMENT '流程类别',
    version      VARCHAR(20)   NOT NULL COMMENT '流程版本',
    is_publish   BIT(1)        NOT NULL DEFAULT b'0' COMMENT '是否发布（0未发布 1已发布 9失效）',
    form_custom  CHAR(1)       NULL     DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
    form_path    VARCHAR(100)  NULL     COMMENT '审批表单路径',
    activity_status BIT(1)     NOT NULL DEFAULT b'1' COMMENT '流程激活状态（0挂起 1激活）',
    listener_type VARCHAR(100) NULL     COMMENT '监听器类型',
    listener_path VARCHAR(400) NULL     COMMENT '监听器路径',
    ext          VARCHAR(500)  NULL     COMMENT '业务详情 存业务表对象json字符串',
    create_time  DATETIME      NULL     COMMENT '创建时间',
    update_time  DATETIME      NULL     COMMENT '更新时间',
    del_flag     CHAR(1)       NULL     DEFAULT '0' COMMENT '删除标志',
    tenant_id    VARCHAR(40)   NULL     COMMENT '租户id',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程定义表';

-- ========== 2. flow_node 流程节点表 ==========
CREATE TABLE IF NOT EXISTS flow_node (
    id            BIGINT        NOT NULL COMMENT '主键id',
    node_type     BIT(1)        NOT NULL COMMENT '节点类型（0开始 1中间 2结束 3互斥网关 4并行网关）',
    definition_id BIGINT        NOT NULL COMMENT '流程定义id',
    node_code     VARCHAR(100)  NOT NULL COMMENT '流程节点编码',
    node_name     VARCHAR(100)  NULL     COMMENT '流程节点名称',
    permission_flag VARCHAR(200) NULL    COMMENT '权限标识（@@隔开多个）',
    node_ratio    DECIMAL(6,3)  NULL     COMMENT '流程签署比例值',
    coordinate    VARCHAR(100)  NULL     COMMENT '坐标',
    any_node_skip VARCHAR(100)  NULL     COMMENT '任意结点跳转',
    listener_type VARCHAR(100)  NULL     COMMENT '监听器类型',
    listener_path VARCHAR(400)  NULL     COMMENT '监听器路径',
    handler_type  VARCHAR(100)  NULL     COMMENT '处理器类型',
    handler_path  VARCHAR(400)  NULL     COMMENT '处理器路径',
    form_custom   CHAR(1)       NULL     DEFAULT 'N' COMMENT '审批表单是否自定义',
    form_path     VARCHAR(100)  NULL     COMMENT '审批表单路径',
    version       VARCHAR(20)   NOT NULL COMMENT '版本',
    create_time   DATETIME      NULL     COMMENT '创建时间',
    update_time   DATETIME      NULL     COMMENT '更新时间',
    ext           TEXT          NULL     COMMENT '节点扩展属性',
    del_flag      CHAR(1)       NULL     DEFAULT '0' COMMENT '删除标志',
    tenant_id     VARCHAR(40)   NULL     COMMENT '租户id',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程节点表';

-- ========== 3. flow_skip 节点跳转关联表 ==========
CREATE TABLE IF NOT EXISTS flow_skip (
    id              BIGINT       NOT NULL COMMENT '主键id',
    definition_id   BIGINT       NOT NULL COMMENT '流程定义id',
    now_node_code   VARCHAR(100) NOT NULL COMMENT '当前流程节点的编码',
    now_node_type   BIT(1)       NULL     COMMENT '当前节点类型',
    next_node_code  VARCHAR(100) NOT NULL COMMENT '下一个流程节点的编码',
    next_node_type  BIT(1)       NULL     COMMENT '下一个节点类型',
    skip_name       VARCHAR(100) NULL     COMMENT '跳转名称',
    skip_type       VARCHAR(40)  NULL     COMMENT '跳转类型（PASS审批通过 REJECT退回）',
    skip_condition  VARCHAR(200) NULL     COMMENT '跳转条件',
    coordinate      VARCHAR(100) NULL     COMMENT '坐标',
    create_time     DATETIME     NULL     COMMENT '创建时间',
    update_time     DATETIME     NULL     COMMENT '更新时间',
    del_flag        CHAR(1)      NULL     DEFAULT '0' COMMENT '删除标志',
    tenant_id       VARCHAR(40)  NULL     COMMENT '租户id',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节点跳转关联表';

-- ========== 4. flow_instance 流程实例表 ==========
CREATE TABLE IF NOT EXISTS flow_instance (
    id              BIGINT       NOT NULL COMMENT '主键id',
    definition_id   BIGINT       NOT NULL COMMENT '对应flow_definition表的id',
    business_id     VARCHAR(40)  NOT NULL COMMENT '业务id',
    node_type       BIT(1)       NOT NULL COMMENT '节点类型',
    node_code       VARCHAR(40)  NOT NULL COMMENT '流程节点编码',
    node_name       VARCHAR(100) NULL     COMMENT '流程节点名称',
    variable        TEXT         NULL     COMMENT '任务变量',
    flow_status     VARCHAR(20)  NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
    activity_status BIT(1)       NOT NULL DEFAULT b'1' COMMENT '流程激活状态（0挂起 1激活）',
    def_json        TEXT         NULL     COMMENT '流程定义json',
    create_by       VARCHAR(64)  NULL     COMMENT '创建者',
    create_time     DATETIME     NULL     COMMENT '创建时间',
    update_time     DATETIME     NULL     COMMENT '更新时间',
    ext             VARCHAR(500) NULL     COMMENT '扩展字段',
    del_flag        CHAR(1)      NULL     DEFAULT '0' COMMENT '删除标志',
    tenant_id       VARCHAR(40)  NULL     COMMENT '租户id',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程实例表';

-- ========== 5. flow_task 待办任务表 ==========
CREATE TABLE IF NOT EXISTS flow_task (
    id            BIGINT       NOT NULL COMMENT '主键id',
    definition_id BIGINT       NOT NULL COMMENT '对应flow_definition表的id',
    instance_id   BIGINT       NOT NULL COMMENT '对应flow_instance表的id',
    node_code     VARCHAR(100) NOT NULL COMMENT '节点编码',
    node_name     VARCHAR(100) NULL     COMMENT '节点名称',
    node_type     BIT(1)       NOT NULL COMMENT '节点类型',
    flow_status   VARCHAR(20)  NOT NULL COMMENT '流程状态',
    form_custom   CHAR(1)      NULL     DEFAULT 'N' COMMENT '审批表单是否自定义',
    form_path     VARCHAR(100) NULL     COMMENT '审批表单路径',
    create_time   DATETIME     NULL     COMMENT '创建时间',
    update_time   DATETIME     NULL     COMMENT '更新时间',
    del_flag      CHAR(1)      NULL     DEFAULT '0' COMMENT '删除标志',
    tenant_id     VARCHAR(40)  NULL     COMMENT '租户id',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='待办任务表';

-- ========== 6. flow_his_task 历史任务记录表 ==========
CREATE TABLE IF NOT EXISTS flow_his_task (
    id              BIGINT       NOT NULL COMMENT '主键id',
    definition_id   BIGINT       NOT NULL COMMENT '对应flow_definition表的id',
    instance_id     BIGINT       NOT NULL COMMENT '对应flow_instance表的id',
    task_id         BIGINT       NOT NULL COMMENT '对应flow_task表的id',
    node_code       VARCHAR(100) NULL     COMMENT '开始节点编码',
    node_name       VARCHAR(100) NULL     COMMENT '开始节点名称',
    node_type       BIT(1)       NULL     COMMENT '开始节点类型',
    target_node_code VARCHAR(200) NULL    COMMENT '目标节点编码',
    target_node_name VARCHAR(200) NULL    COMMENT '目标节点名称',
    approver        VARCHAR(40)  NULL     COMMENT '审批者',
    cooperate_type  BIT(1)       NOT NULL DEFAULT b'0' COMMENT '协作方式(1审批 2转办 3委派 4会签 5票签 6加签 7减签)',
    collaborator    VARCHAR(40)  NULL     COMMENT '协作人',
    skip_type       VARCHAR(10)  NOT NULL COMMENT '流转类型（PASS通过 REJECT退回 NONE无动作）',
    flow_status     VARCHAR(20)  NOT NULL COMMENT '流程状态',
    form_custom     CHAR(1)      NULL     DEFAULT 'N' COMMENT '审批表单是否自定义',
    form_path       VARCHAR(100) NULL     COMMENT '审批表单路径',
    message         VARCHAR(500) NULL     COMMENT '审批意见',
    variable        TEXT         NULL     COMMENT '任务变量',
    ext             TEXT         NULL     COMMENT '业务详情',
    create_time     DATETIME     NULL     COMMENT '任务开始时间',
    update_time     DATETIME     NULL     COMMENT '审批完成时间',
    del_flag        CHAR(1)      NULL     DEFAULT '0' COMMENT '删除标志',
    tenant_id       VARCHAR(40)  NULL     COMMENT '租户id',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='历史任务记录表';

-- ========== 7. flow_user 流程用户表 ==========
CREATE TABLE IF NOT EXISTS flow_user (
    id           BIGINT       NOT NULL COMMENT '主键id',
    type         CHAR(1)      NOT NULL COMMENT '人员类型（1待办审批人 2转办人 3委托人）',
    processed_by VARCHAR(80)  NULL     COMMENT '权限人',
    associated   BIGINT       NOT NULL COMMENT '任务表id',
    create_time  DATETIME     NULL     COMMENT '创建时间',
    create_by    VARCHAR(80)  NULL     COMMENT '创建人',
    update_time  DATETIME     NULL     COMMENT '更新时间',
    del_flag     CHAR(1)      NULL     DEFAULT '0' COMMENT '删除标志',
    tenant_id    VARCHAR(40)  NULL     COMMENT '租户id',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程用户表';

-- ========== 8. 流程管理一级菜单 ==========
-- ID 分配：3500 = 流程管理（顶级目录），避免与 3000 AI 助手冲突
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3500, 0, '流程管理', 'M', '/workflow', NULL, 'guide', 50, 0, 0, 1, NOW(), 0);
