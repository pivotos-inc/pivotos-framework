-- =============================================================
-- PivotOS file 插件 · sys_file 文件元数据表（S25 2.1-F7）
-- 前缀登记：ArchUnit A8 表前缀白名单已为 pivotos-plugin-file 登记 sys_
-- 多租户：tenant_id 行级隔离参与列（sys_file 不在租户内置忽略表清单）
-- 审计字段由 starter-mybatis AuditMetaObjectHandler 自动填充
-- =============================================================

CREATE TABLE sys_file (
    id            BIGINT       NOT NULL COMMENT '文件ID（雪花）',
    object_key    VARCHAR(255) NOT NULL COMMENT '对象键（存储内路径，upload/yyyyMMdd/uuid.ext）',
    original_name VARCHAR(255) NOT NULL DEFAULT '' COMMENT '原始文件名',
    file_size     BIGINT       DEFAULT NULL COMMENT '文件大小（字节）',
    md5           VARCHAR(64)  DEFAULT NULL COMMENT '内容MD5（预留，前端暂不计算）',
    content_type  VARCHAR(128) DEFAULT NULL COMMENT 'MIME 类型',
    storage_type  VARCHAR(32)  NOT NULL DEFAULT 'minio' COMMENT '存储类型（minio/oss/cos/obs/s3）',
    bucket        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '存储桶',
    tenant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID（0=平台/默认租户，行级隔离参与列）',
    create_by     BIGINT       DEFAULT NULL COMMENT '创建人（上传人）',
    create_time   DATETIME     DEFAULT NULL COMMENT '创建时间（上传时间）',
    update_by     BIGINT       DEFAULT NULL COMMENT '更新人',
    update_time   DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (id),
    KEY idx_object_key (object_key),
    -- 文件管理列表场景：(tenant_id, create_time) 倒序分页
    KEY idx_tenant_create (tenant_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文件元数据表';
