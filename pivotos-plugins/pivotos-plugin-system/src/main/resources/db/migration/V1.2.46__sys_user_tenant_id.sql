-- =============================================================
-- V1.2.46：S106 C2 租户管理面——sys_user 增加 tenant_id 租户绑定列
-- 03 库设文档 §3.1 口径：tenant_id bigint（独立库模式可忽略）。
-- 语义：NULL = 平台用户；非 NULL = 租户用户（绑定 sys_tenant.id）。
-- sys_user 在租户内置忽略表内（S14 D1 平台共享表），本列只作绑定/解析用途，
-- 不参与 column 模式行级过滤，与共享表语义不冲突。
-- =============================================================

ALTER TABLE `sys_user`
    ADD COLUMN `tenant_id` BIGINT(20) DEFAULT NULL COMMENT '租户绑定（sys_tenant.id，NULL=平台用户）' AFTER `post_id`,
    ADD KEY `idx_tenant_id` (`tenant_id`);
