-- =====================================================
-- S36: AI Coding 会话暂存表
-- =====================================================
CREATE TABLE IF NOT EXISTS `sys_coding_session` (
    `id`            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `description`   TEXT         NOT NULL                COMMENT '用户自然语言描述',
    `module_name`   VARCHAR(64)  DEFAULT NULL            COMMENT '解析结果：模块名',
    `table_name`    VARCHAR(128) DEFAULT NULL            COMMENT '解析结果：表名',
    `function_name` VARCHAR(64)  DEFAULT NULL            COMMENT '解析结果：功能名称',
    `business_name` VARCHAR(64)  DEFAULT NULL            COMMENT '解析结果：业务名',
    `status`        TINYINT(4)   NOT NULL DEFAULT 0      COMMENT '0=解析中 1=待评审 2=已应用 3=失败',
    `generated_files_json` MEDIUMTEXT DEFAULT NULL       COMMENT '生成文件列表JSON',
    `create_by`     VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `create_time`   DATETIME     DEFAULT NULL            COMMENT '创建时间',
    `update_by`     VARCHAR(64)  DEFAULT NULL            COMMENT '更新人',
    `update_time`   DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding会话暂存表';
