-- =============================================================
-- PivotOS 旧系统迁移插件 · 任务与步骤表初始化
-- =============================================================

CREATE TABLE IF NOT EXISTS `migration_task` (
    `id`                BIGINT(20)    NOT NULL AUTO_INCREMENT    COMMENT '主键',
    `name`              VARCHAR(128)  NOT NULL                   COMMENT '任务名称',
    `description`       VARCHAR(512)  DEFAULT NULL               COMMENT '任务描述',
    `status`            TINYINT(4)    NOT NULL DEFAULT 0         COMMENT '任务状态（见 MigrationTaskStatus 枚举）',
    `backend_framework` VARCHAR(64)   DEFAULT NULL               COMMENT '后端框架，如 spring-boot-2.x',
    `frontend_framework` VARCHAR(64)  DEFAULT NULL               COMMENT '前端框架，如 vue2-options',
    `source_summary`    JSON          DEFAULT NULL               COMMENT '源系统统计信息（JSON）',
    `analysis_report`   JSON          DEFAULT NULL               COMMENT '架构分析报告（JSON）',
    `migration_plan`    JSON          DEFAULT NULL               COMMENT '迁移计划（JSON）',
    `current_step_id`   BIGINT(20)    DEFAULT NULL               COMMENT '当前执行中的步骤 ID',
    `total_steps`       INT(11)       DEFAULT 0                  COMMENT '总步骤数',
    `completed_steps`   INT(11)       DEFAULT 0                  COMMENT '已完成步骤数',
    `rolled_back`       TINYINT(1)    DEFAULT 0                  COMMENT '是否已回滚',
    `tenant_id`         BIGINT(20)    DEFAULT 0                  COMMENT '租户 ID',
    `create_by`         VARCHAR(64)   DEFAULT ''                 COMMENT '创建者',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`         VARCHAR(64)   DEFAULT ''                 COMMENT '更新者',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT(1)    NOT NULL DEFAULT 0         COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迁移任务表';

CREATE TABLE IF NOT EXISTS `migration_step` (
    `id`                 BIGINT(20)    NOT NULL AUTO_INCREMENT    COMMENT '主键',
    `task_id`            BIGINT(20)    NOT NULL                   COMMENT '所属任务',
    `step_no`            INT(11)       NOT NULL                   COMMENT '步骤序号',
    `name`               VARCHAR(128)  NOT NULL                   COMMENT '步骤名称',
    `step_type`          VARCHAR(32)   NOT NULL                   COMMENT '步骤类型：BACKEND/FRONTEND/DB',
    `module_id`          VARCHAR(64)   DEFAULT NULL               COMMENT '关联模块 ID',
    `module_name`        VARCHAR(128)  DEFAULT NULL               COMMENT '关联模块名称',
    `status`             TINYINT(4)    NOT NULL DEFAULT 0         COMMENT '步骤状态',
    `ir_snapshot`        JSON          DEFAULT NULL               COMMENT '本步骤执行前的 IR 快照（JSON）',
    `generated_artifacts` JSON         DEFAULT NULL               COMMENT '本步骤生成的产物清单（JSON）',
    `self_test_result`   JSON          DEFAULT NULL               COMMENT '自测结果（JSON）',
    `review_status`      TINYINT(4)    DEFAULT NULL               COMMENT '评审状态',
    `review_comment`     VARCHAR(1024) DEFAULT NULL               COMMENT '评审意见',
    `git_commit_hash`    VARCHAR(64)   DEFAULT NULL               COMMENT '执行前 Git commit hash',
    `error_msg`          TEXT          DEFAULT NULL               COMMENT '错误信息',
    `start_time`         DATETIME      DEFAULT NULL               COMMENT '开始时间',
    `end_time`           DATETIME      DEFAULT NULL               COMMENT '结束时间',
    `tenant_id`          BIGINT(20)    DEFAULT 0                  COMMENT '租户 ID',
    `create_by`          VARCHAR(64)   DEFAULT ''                 COMMENT '创建者',
    `create_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`          VARCHAR(64)   DEFAULT ''                 COMMENT '更新者',
    `update_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`            TINYINT(1)    NOT NULL DEFAULT 0         COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_status` (`status`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迁移步骤表';

CREATE TABLE IF NOT EXISTS `migration_artifact` (
    `id`              BIGINT(20)    NOT NULL AUTO_INCREMENT    COMMENT '主键',
    `task_id`         BIGINT(20)    NOT NULL                   COMMENT '所属任务',
    `step_id`         BIGINT(20)    NOT NULL                   COMMENT '所属步骤',
    `artifact_type`   VARCHAR(32)   NOT NULL                   COMMENT '产物类型：JAVA/VUE/FLYWAY/OTHER',
    `relative_path`   VARCHAR(512)  NOT NULL                   COMMENT '相对项目根目录的路径',
    `content_hash`    VARCHAR(64)   NOT NULL                   COMMENT '内容哈希',
    `original_content` TEXT         DEFAULT NULL               COMMENT '原代码内容（用于对比）',
    `generated_content` TEXT        DEFAULT NULL               COMMENT '生成的代码内容',
    `applied`         TINYINT(1)    DEFAULT 0                  COMMENT '是否已落盘',
    `tenant_id`       BIGINT(20)    DEFAULT 0                  COMMENT '租户 ID',
    `create_by`       VARCHAR(64)   DEFAULT ''                 COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`       VARCHAR(64)   DEFAULT ''                 COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)    NOT NULL DEFAULT 0         COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_step_id` (`step_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迁移产物表';

-- 系统迁移菜单（系统工具 1100 下，与文档同步 1140 同级）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1150, 1100, '系统迁移',     'C', 'migration', 'system/migration/index', 'migration:task:list',   'upload', 9, 0, 0, 1, NOW(), 0),
    (1151, 1150, '任务查询',     'F', '',          '',                       'migration:task:query',  '',       1, 0, 0, 1, NOW(), 0),
    (1152, 1150, '任务新增',     'F', '',          '',                       'migration:task:create', '',       2, 0, 0, 1, NOW(), 0),
    (1153, 1150, '任务执行',     'F', '',          '',                       'migration:task:execute','',       3, 0, 0, 1, NOW(), 0),
    (1154, 1150, '任务评审',     'F', '',          '',                       'migration:task:review', '',       4, 0, 0, 1, NOW(), 0),
    (1155, 1150, '任务回滚',     'F', '',          '',                       'migration:task:rollback','',      5, 0, 0, 1, NOW(), 0);
