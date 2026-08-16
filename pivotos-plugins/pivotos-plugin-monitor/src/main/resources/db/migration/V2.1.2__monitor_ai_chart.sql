-- =============================================================
-- V2.1.2：S83 AI 图表历史表（monitor 插件首张业务表，mn_ 前缀已登记 A8 白名单）
-- AI 图表由 S72 /monitor/dashboard/ai-chart 生成（ChartSpec 结构化输出），
-- 本表保存用户主动收藏的图表规格，支持历史列表/回放/删除。
-- =============================================================

CREATE TABLE IF NOT EXISTS mn_ai_chart (
    id          BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    user_id     BIGINT       NOT NULL COMMENT '归属用户 ID',
    question    VARCHAR(512) DEFAULT NULL COMMENT '生成时的自然语言描述',
    title       VARCHAR(128) DEFAULT NULL COMMENT '图表标题（冗余自 spec，便于列表展示）',
    chart_type  VARCHAR(16)  NOT NULL COMMENT '图表类型：line / bar / pie（白名单）',
    spec_json   TEXT         NOT NULL COMMENT 'ChartSpec JSON（title/chartType/categories/series/explanation）',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户 ID',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (id),
    KEY idx_mn_ai_chart_user (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 图表历史（S83 报表大屏四期）';
