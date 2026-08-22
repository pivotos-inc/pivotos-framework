-- PivotOS ai 插件 · A2 工具注册与权限体系（S98）
-- ai_tool：@Tool 工具元数据注册表（启动同步器按 ToolCallbackProvider 汇聚结果 upsert）
-- ai_tool_role：工具级角色白名单（无记录 = 登录用户皆可调用；有记录须命中其一，* 为通配）
-- ai_tool_invoke：工具调用审计（全量留痕：成功/失败/越权拒绝/预检拦截）
-- 表前缀 ai_ 已在 ArchUnit A8 白名单（pivotos-plugin-ai 域），无需新增登记

CREATE TABLE ai_tool (
    id                BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    tool_name         VARCHAR(128) NOT NULL COMMENT '工具名（@Tool name，全局唯一）',
    display_name      VARCHAR(128) NULL COMMENT '展示名（缺省同工具名）',
    description       VARCHAR(500) NOT NULL DEFAULT '' COMMENT '工具描述（同步自 @Tool description，超 500 截断）',
    tool_type         VARCHAR(16)  NOT NULL DEFAULT 'read' COMMENT '工具类型（read=只读 write=写操作，@AiToolMeta 声明）',
    confirm_required  TINYINT      NOT NULL DEFAULT 0 COMMENT '写操作是否需二次确认（confirm=true 预检协议）',
    status            TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用，停用后调用一律拒绝）',
    source            VARCHAR(32)  NOT NULL DEFAULT 'register' COMMENT '来源（register=@Tool 扫描自动注册）',
    tenant_id         BIGINT       NULL COMMENT '租户 ID（平台级工具为 NULL）',
    create_by         BIGINT       NULL COMMENT '创建人',
    create_time       DATETIME     NULL COMMENT '创建时间',
    update_by         BIGINT       NULL COMMENT '更新人',
    update_time       DATETIME     NULL COMMENT '更新时间',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_name (tool_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 工具注册表（S98 A2）';

CREATE TABLE ai_tool_role (
    id          BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    tool_id     BIGINT       NOT NULL COMMENT '工具 ID（ai_tool.id）',
    role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码（* 通配所有登录用户）',
    create_by   BIGINT       NULL COMMENT '创建人',
    create_time DATETIME     NULL COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新人',
    update_time DATETIME     NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_role (tool_id, role_code),
    KEY idx_role_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 工具角色白名单（S98 A2）';

CREATE TABLE ai_tool_invoke (
    id          BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    tool_name   VARCHAR(128)  NOT NULL COMMENT '工具名（ai_tool.tool_name）',
    user_id     BIGINT        NULL COMMENT '调用人 ID（上下文缺失为 NULL）',
    tenant_id   BIGINT        NULL COMMENT '租户 ID（随调用上下文）',
    args_summary VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '入参摘要（JSON，超 1000 字符截断）',
    invoke_status VARCHAR(16) NOT NULL DEFAULT 'success' COMMENT '调用状态（success/fail/forbidden/need_confirm）',
    error_msg   VARCHAR(500)  NULL COMMENT '失败/拒绝原因',
    cost_ms     BIGINT        NOT NULL DEFAULT 0 COMMENT '执行耗时（毫秒）',
    trace_id    VARCHAR(64)   NULL COMMENT '链路追踪 ID',
    create_by   BIGINT        NULL COMMENT '创建人',
    create_time DATETIME      NULL COMMENT '创建时间',
    update_by   BIGINT        NULL COMMENT '更新人',
    update_time DATETIME      NULL COMMENT '更新时间',
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (id),
    KEY idx_invoke_tool (tool_name),
    KEY idx_invoke_time (create_time),
    KEY idx_invoke_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 工具调用审计（S98 A2）';
