-- =============================================================
-- PivotOS ai-coding 插件 · sys_coding_session 审计字段修复（S37）
-- 背景：S36 建表时 id 用 AUTO_INCREMENT（撞红线：禁自增 ID）、
--       create_by/update_by 用 VARCHAR，与 AuditMetaObjectHandler
--       填充的 Long 用户 ID 类型不匹配，会话落库直接反射异常。
-- 修复：id 去自增（改雪花 ID，BaseDO ASSIGN_ID），审计人改 BIGINT，
--       补 BaseDO 逻辑删除列 deleted。
-- 存量影响：表内数据为空（此前落库从未成功），DDL 无损。
-- =============================================================

ALTER TABLE `sys_coding_session`
    MODIFY COLUMN `id` BIGINT(20) NOT NULL COMMENT '主键（雪花 ID）',
    MODIFY COLUMN `create_by` BIGINT(20) DEFAULT NULL COMMENT '创建人',
    MODIFY COLUMN `update_by` BIGINT(20) DEFAULT NULL COMMENT '更新人',
    ADD COLUMN `deleted` TINYINT(4) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 删除' AFTER `update_time`;
