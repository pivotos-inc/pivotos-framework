-- =============================================================
-- PivotOS ai-coding 插件 · sys_coding_session 任务类型扩展（S42 / 2.2-F12）
-- 背景：S36/S37 会话仅支持「单表 CRUD」一种任务；S42 Plugin 骨架生成
--       复用同一暂存评审链路，需要区分任务类型并存骨架参数。
-- 变更：task_type（1=单表CRUD 默认，2=Plugin骨架）+ extra_json（任务类型
--       特定参数，如骨架的 pluginName/errorCodeBase/tablePrefix）。
-- 存量影响：既有数据 task_type 回落 1，语义不变。
-- =============================================================

ALTER TABLE `sys_coding_session`
    ADD COLUMN `task_type`  TINYINT(4) NOT NULL DEFAULT 1 COMMENT '任务类型：1=单表CRUD 2=Plugin骨架' AFTER `status`,
    ADD COLUMN `extra_json` MEDIUMTEXT  DEFAULT NULL       COMMENT '任务类型特定参数JSON' AFTER `generated_files_json`,
    ADD KEY `idx_task_type` (`task_type`);
