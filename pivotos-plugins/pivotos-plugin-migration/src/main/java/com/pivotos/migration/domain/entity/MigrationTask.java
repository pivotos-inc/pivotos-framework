package com.pivotos.migration.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 迁移任务主表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("migration_task")
public class MigrationTask extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 任务名称 */
    private String name;

    /** 任务描述 */
    private String description;

    /** 任务状态（见 MigrationTaskStatus 枚举） */
    private Integer status;

    /** 后端框架，如 spring-boot-2.x */
    private String backendFramework;

    /** 前端框架，如 vue2-options */
    private String frontendFramework;

    /** 源系统统计信息（JSON） */
    private String sourceSummary;

    /** 架构分析报告（JSON） */
    private String analysisReport;

    /** 迁移计划（JSON） */
    private String migrationPlan;

    /** 当前执行中的步骤 ID */
    private Long currentStepId;

    /** 总步骤数 */
    private Integer totalSteps;

    /** 已完成步骤数 */
    private Integer completedSteps;

    /** 是否已回滚 */
    private Boolean rolledBack;
}
