-- V1.2.53  warm-flow 表单表 flow_form（引擎内置表单能力的第 9 张表）
--
-- 【为什么补这张表】
-- V3.1-C 取证：`GET /warm-flow/published-form` 登录后恒 500，日志
--   java.sql.SQLSyntaxErrorException: Table 'pivotos_dev.flow_form' doesn't exist
-- 根因：warm-flow 的 `warm-flow-plugin-ui-sb-web` 自带表单端点（published-form / save-form-content /
-- get-form-content …），其 `WarmFlowService.publishedForm()` → `FlowEngine.formService().list()` →
-- `FlowFormDaoImpl` 必然查 `flow_form`；而 PivotOS 的 V1.2.21 只建了 8 张引擎表
-- （flow_cc / flow_definition / flow_his_task / flow_instance / flow_node / flow_skip / flow_task / flow_user），
-- 缺的正是表单表。**「85 条迁移一次过」不等于「schema 与全部依赖方自洽」**。
--
-- 【列以运行时实体为准，不以文档记忆为准】
-- 本表 15 列来自javap 反编译 warm-flow-mybatis-plus-core 1.8.7 的
-- `org.dromara.warm.flow.orm.entity.FlowForm`（V3.1-A/C 报告里写的「13 列」是当时按
-- 官方 DDL 片段估的，以 javap 实测为准）：
--   id / createTime / updateTime / createBy / updateBy / delFlag / formCode / formName / version /
--   isPublish / formType / formPath / formContent / ext / tenantId
-- 列类型沿用本仓 V1.2.21 的既有风格（BIT(1) 表布尔、CHAR(1) 表标志、VARCHAR(40) 表租户）。
--
-- 【注意】
-- 1. 表前缀 `flow_` 合规（ArchUnit A8 已登记 pivotos-plugin-workflow → flow_）。
-- 2. 这是**新增低版本号迁移**（V1.2.53 < 已应用的 2.2.2）：未开 out-of-order 的库会
--    `Validate failed: Detected resolved migration not applied to database`，
--    故全量 IT 前必须重建 8 个测试库（本项目已踩过两次，勿忘）。
-- 3. 与 warm-flow 1.8.7→1.8.9 升级**正交**：1.8.9 的 FlowForm 字段与 1.8.7 diff 为空，
--    升级不会加列也不会移除端点。

-- ========== flow_form 流程表单表 ==========
CREATE TABLE IF NOT EXISTS flow_form (
    id           BIGINT        NOT NULL COMMENT '主键id',
    create_time  DATETIME      NULL     COMMENT '创建时间',
    update_time  DATETIME      NULL     COMMENT '更新时间',
    create_by    VARCHAR(64)   NULL     COMMENT '创建人',
    update_by    VARCHAR(64)   NULL     COMMENT '更新人',
    del_flag     CHAR(1)       NULL     DEFAULT '0' COMMENT '删除标志',
    form_code    VARCHAR(40)   NULL     COMMENT '表单编码',
    form_name    VARCHAR(100)  NULL     COMMENT '表单名称',
    version      VARCHAR(20)   NULL     COMMENT '版本',
    is_publish   BIT(1)        NULL     DEFAULT b'0' COMMENT '是否发布（0未发布 1已发布 9失效）',
    form_type    INT           NULL     COMMENT '表单类型（1静态表单 2动态表单）',
    form_path    VARCHAR(100)  NULL     COMMENT '表单路径',
    form_content LONGTEXT      NULL     COMMENT '表单内容（表单 JSON schema，故用 LONGTEXT 而非 TEXT）',
    ext          VARCHAR(500)  NULL     COMMENT '扩展字段',
    tenant_id    VARCHAR(40)   NULL     COMMENT '租户id',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程表单表';
