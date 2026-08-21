-- =============================================================
-- PivotOS 旧系统迁移插件 · 补齐数据模型（文件索引 / IR 节点 / 评审 / 日志）
-- 同时修正 V2.1.4 中审计字段类型，与 BaseDO/TenantBaseDO 保持一致。
-- =============================================================

-- ---------- 修正 V2.1.4 已建表的审计字段类型，统一为 BIGINT ----------
ALTER TABLE `migration_task`
    MODIFY COLUMN `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
    MODIFY COLUMN `update_by` BIGINT DEFAULT NULL COMMENT '更新者';

ALTER TABLE `migration_step`
    MODIFY COLUMN `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
    MODIFY COLUMN `update_by` BIGINT DEFAULT NULL COMMENT '更新者';

ALTER TABLE `migration_artifact`
    MODIFY COLUMN `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
    MODIFY COLUMN `update_by` BIGINT DEFAULT NULL COMMENT '更新者';

-- ---------- 源文件索引表 ----------
CREATE TABLE IF NOT EXISTS `migration_file` (
    `id`              BIGINT(20)    NOT NULL                COMMENT '主键（雪花 ID）',
    `task_id`         BIGINT(20)    NOT NULL                COMMENT '所属任务',
    `file_type`       VARCHAR(16)   NOT NULL                COMMENT '文件类型：BACKEND/FRONTEND',
    `relative_path`   VARCHAR(512)  NOT NULL                COMMENT '相对解压根目录的路径',
    `file_name`       VARCHAR(256)  NOT NULL                COMMENT '文件名',
    `extension`       VARCHAR(32)   DEFAULT NULL            COMMENT '扩展名',
    `file_size`       BIGINT(20)    DEFAULT 0               COMMENT '文件大小（字节）',
    `content_hash`    VARCHAR(64)   DEFAULT NULL            COMMENT '内容哈希（SHA-256）',
    `parsed`          TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否已解析：0 否 1 是',
    `tenant_id`       BIGINT(20)    DEFAULT 0               COMMENT '租户 ID',
    `create_by`       BIGINT(20)    DEFAULT NULL            COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`       BIGINT(20)    DEFAULT NULL            COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_file_type` (`file_type`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迁移源文件索引';

-- ---------- IR 节点表 ----------
CREATE TABLE IF NOT EXISTS `migration_ir_node` (
    `id`              BIGINT(20)    NOT NULL                COMMENT '主键（雪花 ID）',
    `task_id`         BIGINT(20)    NOT NULL                COMMENT '所属任务',
    `node_type`       VARCHAR(32)   NOT NULL                COMMENT '节点类型：PROJECT/MODULE/TABLE/ENTITY/ROUTE/SERVICE/PAGE/COMPONENT',
    `node_id`         VARCHAR(128)  NOT NULL                COMMENT '节点唯一标识（IR 内）',
    `parent_node_id`  VARCHAR(128)  DEFAULT NULL            COMMENT '父节点标识',
    `module_id`       VARCHAR(64)   DEFAULT NULL            COMMENT '关联模块 ID',
    `name`            VARCHAR(256)  DEFAULT NULL            COMMENT '节点名称',
    `source_path`     VARCHAR(512)  DEFAULT NULL            COMMENT '源文件相对路径',
    `payload`         JSON          DEFAULT NULL            COMMENT '节点载荷（IR 属性 JSON）',
    `relations`       JSON          DEFAULT NULL            COMMENT '节点关系（依赖/引用 JSON 数组）',
    `tenant_id`       BIGINT(20)    DEFAULT 0               COMMENT '租户 ID',
    `create_by`       BIGINT(20)    DEFAULT NULL            COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`       BIGINT(20)    DEFAULT NULL            COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_node_type` (`node_type`),
    KEY `idx_node_id` (`node_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迁移 IR 节点';

-- ---------- 人工评审记录表 ----------
CREATE TABLE IF NOT EXISTS `migration_review` (
    `id`              BIGINT(20)    NOT NULL                COMMENT '主键（雪花 ID）',
    `task_id`         BIGINT(20)    NOT NULL                COMMENT '所属任务',
    `step_id`         BIGINT(20)    DEFAULT NULL            COMMENT '关联步骤（任务级评审可为 NULL）',
    `reviewer_id`     BIGINT(20)    DEFAULT NULL            COMMENT '评审人 ID',
    `action`          VARCHAR(16)   NOT NULL                COMMENT '评审动作：PASS/REJECT/MODIFY',
    `comment`         VARCHAR(2048) DEFAULT NULL            COMMENT '评审意见',
    `modifications`   JSON          DEFAULT NULL            COMMENT '修改内容（JSON）',
    `tenant_id`       BIGINT(20)    DEFAULT 0               COMMENT '租户 ID',
    `create_by`       BIGINT(20)    DEFAULT NULL            COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`       BIGINT(20)    DEFAULT NULL            COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_step_id` (`step_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迁移人工评审记录';

-- ---------- 执行日志表 ----------
CREATE TABLE IF NOT EXISTS `migration_log` (
    `id`              BIGINT(20)    NOT NULL                COMMENT '主键（雪花 ID）',
    `task_id`         BIGINT(20)    NOT NULL                COMMENT '所属任务',
    `step_id`         BIGINT(20)    DEFAULT NULL            COMMENT '关联步骤（任务级日志可为 NULL）',
    `log_level`       VARCHAR(16)   NOT NULL                COMMENT '日志级别：DEBUG/INFO/WARN/ERROR',
    `phase`           VARCHAR(32)   DEFAULT NULL            COMMENT '执行阶段：UPLOAD/PARSE/ANALYZE/PLAN/GENERATE/SELF_TEST/REVIEW/ROLLBACK',
    `message`         TEXT          NOT NULL                COMMENT '日志内容',
    `metadata`        JSON          DEFAULT NULL            COMMENT '扩展信息（JSON）',
    `tenant_id`       BIGINT(20)    DEFAULT 0               COMMENT '租户 ID',
    `create_by`       BIGINT(20)    DEFAULT NULL            COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`       BIGINT(20)    DEFAULT NULL            COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_step_id` (`step_id`),
    KEY `idx_log_level` (`log_level`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迁移执行日志';
