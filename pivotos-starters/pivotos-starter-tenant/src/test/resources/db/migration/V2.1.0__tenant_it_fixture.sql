-- =============================================================
-- tenant Starter 集成测试夹具表
-- t_tenant_demo：租户业务表（含 tenant_id）；t_platform：平台共享表（无 tenant_id）
-- =============================================================
CREATE TABLE t_tenant_demo (
                               id          BIGINT       NOT NULL COMMENT '主键（雪花）',
                               title       VARCHAR(128) NOT NULL COMMENT '标题',
                               tenant_id   BIGINT       DEFAULT NULL COMMENT '租户ID',
                               create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                               create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                               update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                               update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                               deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                               PRIMARY KEY (id),
                               KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'tenant Starter 测试夹具（租户表）';

CREATE TABLE t_platform (
                            id          BIGINT       NOT NULL COMMENT '主键（雪花）',
                            name        VARCHAR(128) NOT NULL COMMENT '名称',
                            create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                            create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                            update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                            update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                            deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                            PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'tenant Starter 测试夹具（平台共享表）';
