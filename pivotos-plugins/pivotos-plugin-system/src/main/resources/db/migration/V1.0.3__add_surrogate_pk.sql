-- V1.0.3：关联表补代理主键（治理 MP @TableId 告警，恢复 xxById 可用性）
-- 约定：主键为应用侧雪花 ID（禁自增红线）；原复合列保留唯一键保证数据完整性；
--      存量行用确定性序列回填（已合入脚本永不改写，只新增迁移）。
-- 注意：ADD COLUMN 的位置子句 FIRST/AFTER 必须放在列定义之后（MySQL 语法）

ALTER TABLE sys_user_role ADD COLUMN id BIGINT NULL COMMENT '主键（雪花）' FIRST;
SET @id := 300000000000000000;
UPDATE sys_user_role SET id = (@id := @id + 1);
ALTER TABLE sys_user_role MODIFY COLUMN id BIGINT NOT NULL COMMENT '主键（雪花）',
DROP PRIMARY KEY, ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_user_role (user_id, role_id);

ALTER TABLE sys_user_post ADD COLUMN id BIGINT NULL COMMENT '主键（雪花）' FIRST;
SET @id := 300000000000000000;
UPDATE sys_user_post SET id = (@id := @id + 1);
ALTER TABLE sys_user_post MODIFY COLUMN id BIGINT NOT NULL COMMENT '主键（雪花）',
DROP PRIMARY KEY, ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_user_post (user_id, post_id);

ALTER TABLE sys_role_menu ADD COLUMN id BIGINT NULL COMMENT '主键（雪花）' FIRST;
SET @id := 300000000000000000;
UPDATE sys_role_menu SET id = (@id := @id + 1);
ALTER TABLE sys_role_menu MODIFY COLUMN id BIGINT NOT NULL COMMENT '主键（雪花）',
DROP PRIMARY KEY, ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_role_menu (role_id, menu_id);
