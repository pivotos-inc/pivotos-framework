-- =====================================================
-- V1.2.9: 代码生成器 — 业务表 & 字段配置
-- =====================================================

CREATE TABLE IF NOT EXISTS `sys_gen_table` (
    `id`              BIGINT        NOT NULL COMMENT '编号',
    `table_name`      VARCHAR(200)  DEFAULT '' COMMENT '表名称',
    `table_comment`   VARCHAR(500)  DEFAULT '' COMMENT '表描述',
    `class_name`      VARCHAR(100)  DEFAULT '' COMMENT '实体类名',
    `package_name`    VARCHAR(200)  DEFAULT '' COMMENT '父包名路径',
    `module_name`     VARCHAR(100)  DEFAULT '' COMMENT '模块名',
    `business_name`   VARCHAR(100)  DEFAULT '' COMMENT '业务名',
    `function_name`   VARCHAR(200)  DEFAULT '' COMMENT '功能名称',
    `function_author` VARCHAR(100)  DEFAULT '' COMMENT '生成功能作者',
    `gen_type`        CHAR(1)       DEFAULT '0' COMMENT '生成方式（0 zip下载 1 写入工程）',
    `gen_path`        VARCHAR(500)  DEFAULT '' COMMENT '生成路径',
    `remark`          VARCHAR(500)  DEFAULT '' COMMENT '备注',
    `create_by`       BIGINT        DEFAULT NULL COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT        DEFAULT NULL COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_table_name` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='代码生成业务表';

CREATE TABLE IF NOT EXISTS `sys_gen_table_column` (
    `id`              BIGINT        NOT NULL COMMENT '编号',
    `table_id`        BIGINT        DEFAULT NULL COMMENT '归属表编号',
    `column_name`     VARCHAR(200)  DEFAULT '' COMMENT '列名称',
    `column_comment`  VARCHAR(500)  DEFAULT '' COMMENT '列描述',
    `column_type`     VARCHAR(100)  DEFAULT '' COMMENT '列类型',
    `java_type`       VARCHAR(100)  DEFAULT '' COMMENT 'JAVA类型',
    `java_field`      VARCHAR(200)  DEFAULT '' COMMENT 'JAVA字段名',
    `is_pk`           TINYINT       DEFAULT 0 COMMENT '是否主键（1是）',
    `is_increment`    TINYINT       DEFAULT 0 COMMENT '是否自增（1是）',
    `is_required`     TINYINT       DEFAULT 0 COMMENT '是否必填（1是）',
    `is_insert`       TINYINT       DEFAULT 1 COMMENT '是否插入字段（1是）',
    `is_edit`         TINYINT       DEFAULT 1 COMMENT '是否编辑字段（1是）',
    `is_list`         TINYINT       DEFAULT 1 COMMENT '是否列表字段（1是）',
    `is_query`        TINYINT       DEFAULT 0 COMMENT '是否查询字段（1是）',
    `query_type`      VARCHAR(50)   DEFAULT 'EQ' COMMENT '查询方式（EQ等于、NE不等于、GT大于、LT小于、LIKE模糊、BETWEEN范围）',
    `html_type`       VARCHAR(50)   DEFAULT '' COMMENT '显示类型（input、textarea、select、radio、checkbox、datetime、imageUpload）',
    `dict_type`       VARCHAR(200)  DEFAULT '' COMMENT '字典类型',
    `sort`            INT           DEFAULT 0 COMMENT '排序',
    `create_by`       BIGINT        DEFAULT NULL COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT        DEFAULT NULL COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_table_id` (`table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='代码生成业务表字段';
