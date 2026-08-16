-- =============================================================
-- PivotOS system 插件 · 定时任务管理表（S89 定时任务控制台）
-- sys_job 平台共享表（雪花 ID，已登记租户内置忽略表）+ 菜单。
-- super_admin 走通配权限，无需 sys_role_menu 授权数据。
-- =============================================================

CREATE TABLE IF NOT EXISTS `sys_job` (
    `id`                        BIGINT(20)    NOT NULL                COMMENT '主键（雪花 ID）',
    `job_handler`               VARCHAR(100)  NOT NULL                COMMENT '@XxlJob handler 名',
    `job_name`                  VARCHAR(100)  NOT NULL                COMMENT '任务显示名',
    `schedule_type`             VARCHAR(20)   NOT NULL DEFAULT 'NONE' COMMENT '调度类型：NONE/CRON/FIX_RATE',
    `schedule_conf`             VARCHAR(200)  DEFAULT NULL            COMMENT '调度配置（Cron 表达式或固定速率秒数）',
    `executor_param`            VARCHAR(500)  DEFAULT NULL            COMMENT '任务参数',
    `misfire_strategy`          VARCHAR(20)   NOT NULL DEFAULT 'FIRE_ONCE_NOW' COMMENT '过期策略：DO_NOTHING/FIRE_ONCE_NOW',
    `executor_route_strategy`  VARCHAR(20)   NOT NULL DEFAULT 'ROUND' COMMENT '路由策略：FIRST/LAST/ROUND/...',
    `executor_block_strategy`  VARCHAR(20)   NOT NULL DEFAULT 'SERIAL_EXECUTION' COMMENT '阻塞策略：SERIAL_EXECUTION/DISCARD_LATER/COVER_EARLY',
    `executor_timeout`          INT(11)       NOT NULL DEFAULT 0      COMMENT '执行超时秒数（0=不限）',
    `executor_fail_retry_count` INT(11)       NOT NULL DEFAULT 0      COMMENT '失败重试次数（0=不重试）',
    `trigger_status`            TINYINT(4)    NOT NULL DEFAULT 0      COMMENT '调度状态：0 暂停 1 运行',
    `xxl_job_id`                INT(11)       DEFAULT NULL            COMMENT 'XXL-Job 侧 job ID（同步后回写）',
    `create_by`                 BIGINT(20)    DEFAULT NULL            COMMENT '创建人',
    `create_time`               DATETIME      DEFAULT NULL            COMMENT '创建时间',
    `update_by`                 BIGINT(20)    DEFAULT NULL            COMMENT '更新人',
    `update_time`               DATETIME      DEFAULT NULL            COMMENT '更新时间',
    `deleted`                   TINYINT(4)    NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_handler` (`job_handler`),
    KEY `idx_trigger_status` (`trigger_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务管理';

-- 定时任务管理菜单（系统工具 1100 下，与任务执行记录 1130 同级）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1133, 1100, '定时任务',     'C', 'job', 'system/job/index', 'system:job:list',          'timer',  2, 0, 0, 1, NOW(), 0),
    (1134, 1133, '任务查询',     'F', '',     '',                  'system:job:query',        '',        1, 0, 0, 1, NOW(), 0),
    (1135, 1133, '任务新增',     'F', '',     '',                  'system:job:add',          '',        2, 0, 0, 1, NOW(), 0),
    (1136, 1133, '任务修改',     'F', '',     '',                  'system:job:edit',         '',        3, 0, 0, 1, NOW(), 0),
    (1137, 1133, '任务删除',     'F', '',     '',                  'system:job:remove',       '',        4, 0, 0, 1, NOW(), 0),
    (1138, 1133, '手动触发',     'F', '',     '',                  'system:job:trigger',      '',        5, 0, 0, 1, NOW(), 0),
    (1139, 1133, '启停切换',     'F', '',     '',                  'system:job:changeStatus', '',        6, 0, 0, 1, NOW(), 0);
