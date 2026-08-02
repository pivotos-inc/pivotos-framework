-- =============================================================
-- PivotOS system 插件 · 定时任务执行记录（S34 可视化，S37）
-- sys_job_log 平台共享表（雪花 ID，已登记租户内置忽略表）+ 菜单。
-- super_admin 走通配权限，无需 sys_role_menu 授权数据。
-- =============================================================

CREATE TABLE IF NOT EXISTS `sys_job_log` (
    `id`           BIGINT(20)   NOT NULL                COMMENT '主键（雪花 ID）',
    `job_handler`  VARCHAR(100) NOT NULL                COMMENT 'XXL-Job handler 名',
    `status`       TINYINT(4)   NOT NULL DEFAULT 0      COMMENT '结果（0成功 1失败）',
    `error_msg`    VARCHAR(2000) DEFAULT NULL           COMMENT '异常信息（失败时）',
    `duration`     BIGINT(20)    DEFAULT NULL           COMMENT '耗时（毫秒）',
    `execute_time` DATETIME      DEFAULT NULL           COMMENT '执行时间',
    `create_by`    BIGINT(20)    DEFAULT NULL           COMMENT '创建人',
    `create_time`  DATETIME      DEFAULT NULL           COMMENT '创建时间',
    `update_by`    BIGINT(20)    DEFAULT NULL           COMMENT '更新人',
    `update_time`  DATETIME      DEFAULT NULL           COMMENT '更新时间',
    `deleted`      TINYINT(4)   NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_handler` (`job_handler`),
    KEY `idx_execute_time` (`execute_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务执行记录';

-- 任务执行记录（系统工具 1100 下）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (1130, 1100, '任务执行记录', 'C', 'jobLog', 'system/jobLog/index', 'system:joblog:list',    'document', 3, 0, 0, 1, NOW(), 0),
    (1131, 1130, '记录查询',     'F', '', '', 'system:joblog:query',   '', 1, 0, 0, 1, NOW(), 0),
    (1132, 1130, '手动触发',     'F', '', '', 'system:joblog:trigger', '', 2, 0, 0, 1, NOW(), 0);
