package com.pivotos.migration.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 迁移执行日志表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("migration_log")
public class MigrationLog extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 所属任务 */
    private Long taskId;

    /** 关联步骤（任务级日志可为 NULL） */
    private Long stepId;

    /** 日志级别：DEBUG/INFO/WARN/ERROR */
    private String logLevel;

    /** 执行阶段：UPLOAD/PARSE/ANALYZE/PLAN/GENERATE/SELF_TEST/REVIEW/ROLLBACK */
    private String phase;

    /** 日志内容 */
    private String message;

    /** 扩展信息（JSON） */
    private String metadata;
}
