-- =====================================================
-- PivotOS S30 数据权限：sys_role 表数据范围字段改造
-- 版本：v2.1.0-M7
-- 日期：2026-08-01
-- 迁移：V1.2.8
-- =====================================================

-- 1. 新增 data_scope 字段（数据范围类型）
ALTER TABLE sys_role
    ADD COLUMN data_scope TINYINT NOT NULL DEFAULT 1
        COMMENT '数据范围（1全部数据权限 2本部门 3本部门及以下 4仅本人 5自定义部门）';

-- 2. 新增 custom_dept_ids 字段（自定义部门 ID 集合）
ALTER TABLE sys_role
    ADD COLUMN custom_dept_ids VARCHAR(2000) DEFAULT NULL
        COMMENT '自定义部门ID集合（逗号分隔），data_scope=5 时有效';

-- 3. 为现有超级管理员角色保留全部数据权限（已经是默认值 1，无需额外操作）
-- 普通角色默认 data_scope=1（全部数据权限），管理员可在角色管理页调整
