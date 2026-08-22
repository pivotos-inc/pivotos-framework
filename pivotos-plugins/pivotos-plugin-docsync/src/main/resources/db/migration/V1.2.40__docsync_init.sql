-- =============================================================
-- PivotOS docsync 插件 · API 文档多平台同步配置与日志表
-- 支持 Torna/YApi/Apifox/ShowDoc/XXL-API/ApiPost/Eolink 多平台同步
-- =============================================================

CREATE TABLE IF NOT EXISTS `doc_sync_config` (
    `id`                   BIGINT(20)    NOT NULL                COMMENT '主键（雪花 ID）',
    `name`                 VARCHAR(100)  NOT NULL                COMMENT '配置名称（如"测试环境-Torna"）',
    `platform_type`        VARCHAR(20)   NOT NULL                COMMENT '平台类型：TORNA/YAPI/APIFOX/SHOWDOC/XXL_API/APIPOST/EOLINK',
    `server_url`           VARCHAR(500)  NOT NULL                COMMENT '服务器地址',
    `credential`           VARCHAR(500)  DEFAULT NULL            COMMENT '认证凭证（Token / API Key）',
    `secondary_credential` VARCHAR(500)  DEFAULT NULL            COMMENT '辅助凭证（ShowDoc api_token 等）',
    `project_id`           VARCHAR(100)  DEFAULT NULL            COMMENT '项目 ID（Apifox/YApi 需要）',
    `enabled`              TINYINT(4)    NOT NULL DEFAULT 1      COMMENT '是否启用：1 启用 0 禁用',
    `last_sync_time`       DATETIME      DEFAULT NULL            COMMENT '上次同步时间',
    `last_sync_status`     VARCHAR(20)   DEFAULT NULL            COMMENT '上次同步状态：SUCCESS/FAILED/TIMEOUT/MANUAL_EXPORT',
    `last_sync_result`     VARCHAR(1000) DEFAULT NULL            COMMENT '上次同步结果描述',
    `create_by`            BIGINT(20)    DEFAULT NULL            COMMENT '创建人',
    `create_time`          DATETIME      DEFAULT NULL            COMMENT '创建时间',
    `update_by`           BIGINT(20)    DEFAULT NULL            COMMENT '更新人',
    `update_time`          DATETIME      DEFAULT NULL            COMMENT '更新时间',
    `deleted`              TINYINT(4)    NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_platform_type` (`platform_type`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档同步配置';

CREATE TABLE IF NOT EXISTS `doc_sync_log` (
    `id`              BIGINT(20)    NOT NULL                COMMENT '主键（雪花 ID）',
    `config_id`       BIGINT(20)    NOT NULL                COMMENT '同步配置 ID',
    `platform_type`   VARCHAR(20)   NOT NULL                COMMENT '平台类型',
    `status`          VARCHAR(20)   NOT NULL                COMMENT '同步状态：SUCCESS/FAILED/TIMEOUT/MANUAL_EXPORT',
    `api_count`       INT(11)       DEFAULT 0               COMMENT '同步的接口数量',
    `elapsed_ms`      BIGINT(20)    DEFAULT 0               COMMENT '耗时（毫秒）',
    `result`          VARCHAR(2000) DEFAULT NULL            COMMENT '结果描述',
    `sync_time`       DATETIME      NOT NULL                COMMENT '同步时间',
    `create_by`       BIGINT(20)    DEFAULT NULL            COMMENT '创建人',
    `create_time`     DATETIME      DEFAULT NULL            COMMENT '创建时间',
    `update_by`      BIGINT(20)    DEFAULT NULL            COMMENT '更新人',
    `update_time`     DATETIME      DEFAULT NULL            COMMENT '更新时间',
    `deleted`         TINYINT(4)    NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_config_id` (`config_id`),
    KEY `idx_sync_time` (`sync_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档同步日志';

-- 同步配置管理菜单（系统工具 1100 下，与定时任务 1133 同级）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1140, 1100, '文档同步',     'C', 'docsync', 'system/docsync/index', 'system:docsync:list',   'documentation', 8, 0, 0, 1, NOW(), 0),
    (1141, 1140, '配置查询',     'F', '',         '',                     'system:docsync:query',  '',               1, 0, 0, 1, NOW(), 0),
    (1142, 1140, '配置新增',     'F', '',         '',                     'system:docsync:add',    '',               2, 0, 0, 1, NOW(), 0),
    (1143, 1140, '配置修改',     'F', '',         '',                     'system:docsync:edit',   '',               3, 0, 0, 1, NOW(), 0),
    (1144, 1140, '配置删除',     'F', '',         '',                     'system:docsync:remove', '',               4, 0, 0, 1, NOW(), 0),
    (1145, 1140, '测试连接',     'F', '',         '',                     'system:docsync:test',   '',               5, 0, 0, 1, NOW(), 0),
    (1146, 1140, '立即同步',     'F', '',         '',                     'system:docsync:sync',   '',               6, 0, 0, 1, NOW(), 0);
