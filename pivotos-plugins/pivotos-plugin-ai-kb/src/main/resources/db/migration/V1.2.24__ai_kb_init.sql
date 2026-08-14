-- =============================================================
-- PivotOS ai-kb 插件 · RAG 知识库表 + 菜单（S58）
-- 表前缀：ai_kb_（ArchUnit A8 已登记 pivotos-plugin-ai-kb -> ai_kb_）
-- =============================================================

-- ========== 1. ai_kb_base 知识库表 ==========
CREATE TABLE IF NOT EXISTS ai_kb_base (
    id              BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    name            VARCHAR(100)  NOT NULL COMMENT '知识库名称',
    description     VARCHAR(500)  NULL     COMMENT '知识库描述',
    vector_store_type VARCHAR(20) NOT NULL DEFAULT 'simple' COMMENT '向量存储类型（simple / milvus / pgvector / qdrant）',
    embedding_model VARCHAR(64)   NULL     COMMENT 'Embedding 模型标识（为空则使用系统默认）',
    chunk_size      INT           NOT NULL DEFAULT 500 COMMENT '默认分块大小（字符数）',
    chunk_overlap   INT           NOT NULL DEFAULT 100 COMMENT '默认分块重叠（字符数）',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    tenant_id       BIGINT        NULL     COMMENT '租户ID',
    create_by       BIGINT        NULL,
    create_time     DATETIME      NULL,
    update_by       BIGINT        NULL,
    update_time     DATETIME      NULL,
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_id (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识库表';

-- ========== 2. ai_kb_document 知识库文档表 ==========
CREATE TABLE IF NOT EXISTS ai_kb_document (
    id            BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    kb_id         BIGINT        NOT NULL COMMENT '所属知识库ID',
    file_name     VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    file_url      VARCHAR(500)  NOT NULL COMMENT '文件访问 URL 或对象 key',
    file_type     VARCHAR(50)   NULL     COMMENT '文件 MIME 类型',
    file_size     BIGINT        NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    chunk_size    INT           NOT NULL DEFAULT 500 COMMENT '实际分块大小',
    chunk_overlap INT           NOT NULL DEFAULT 100 COMMENT '实际分块重叠',
    status        TINYINT       NOT NULL DEFAULT 0 COMMENT '文档状态（0待处理 1向量化中 2已完成 3失败）',
    error_msg     VARCHAR(1000) NULL     COMMENT '最近一次失败原因',
    vector_count  INT           NOT NULL DEFAULT 0 COMMENT '已写入向量库的文本块数量',
    tenant_id     BIGINT        NULL     COMMENT '租户ID',
    create_by     BIGINT        NULL,
    create_time   DATETIME      NULL,
    update_by     BIGINT        NULL,
    update_time   DATETIME      NULL,
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_kb_id (kb_id),
    KEY idx_tenant_id (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 知识库文档表';

-- ========== 3. 知识库菜单（挂在 AI 助手 3000 下） ==========
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted)
VALUES (3040, 3000, '知识库', 'C', 'kb', 'ai/kb/index', 'ai:kb:list', 'knowledge', 4, 0, 0, 1, NOW(), 0);

-- ========== 4. 知识库按钮权限 ==========
INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3041, 3040, '知识库新增', 'F', 'ai:kb:add', 1, 0, 0, 1, NOW(), 0);

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3042, 3040, '知识库修改', 'F', 'ai:kb:edit', 2, 0, 0, 1, NOW(), 0);

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3043, 3040, '知识库删除', 'F', 'ai:kb:delete', 3, 0, 0, 1, NOW(), 0);

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3044, 3040, '文档上传', 'F', 'ai:kb:doc:add', 4, 0, 0, 1, NOW(), 0);

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3045, 3040, '文档删除', 'F', 'ai:kb:doc:delete', 5, 0, 0, 1, NOW(), 0);

INSERT IGNORE INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort, visible, status, create_by, create_time, deleted)
VALUES (3046, 3040, '文档重新向量化', 'F', 'ai:kb:doc:reindex', 6, 0, 0, 1, NOW(), 0);
