-- =============================================================
-- PivotOS ai-coding 插件 · sys_coding_session 修改型任务（A4-2 / S111）
-- 背景：A4-1（S110）落地两段定位后，修改型链路需要暂存「定位结果 → 结构化
--       edit 指令 → 渲染后 diff → 门禁结果」四段产物，供评审面（A4-3 / S112）
--       展示与人工裁决；task_type 追加 5=修改型（1=单表CRUD 2=Plugin骨架
--       3=主子表 4=树表 已占用，文档口径的 3 与本表实际冲突，改用 5）。
-- 变更：四列 MEDIUMTEXT + task_type 注释更新（存量数据语义不变）。
-- 序列说明：本表自 V1.2.10 建表起一直走 system 域 V1.2.x 序列（V1.2.14 /
--       V1.2.17 同），故本次续 V1.2.47 而非 ai 域 V2.1.13——同表变更保持
--       单一序列可追溯。ai 域 V2.1.13 保留给后续 ai_ 前缀新表。
-- =============================================================

ALTER TABLE `sys_coding_session`
    ADD COLUMN `locate_json` MEDIUMTEXT DEFAULT NULL COMMENT 'A4-1 定位结果快照JSON（chosen/候选/仲裁分）' AFTER `extra_json`,
    ADD COLUMN `edit_json`   MEDIUMTEXT DEFAULT NULL COMMENT '结构化 edit 指令JSON（search/replace 块）' AFTER `locate_json`,
    ADD COLUMN `diff_text`   MEDIUMTEXT DEFAULT NULL COMMENT '确定性渲染的 unified diff（评审面展示物）' AFTER `edit_json`,
    ADD COLUMN `gate_json`   MEDIUMTEXT DEFAULT NULL COMMENT '自动门禁结果JSON（apply-check/编译/typecheck）' AFTER `diff_text`;

ALTER TABLE `sys_coding_session`
    MODIFY COLUMN `task_type` TINYINT(4) NOT NULL DEFAULT 1 COMMENT '任务类型：1=单表CRUD 2=Plugin骨架 3=主子表 4=树表 5=修改型';
