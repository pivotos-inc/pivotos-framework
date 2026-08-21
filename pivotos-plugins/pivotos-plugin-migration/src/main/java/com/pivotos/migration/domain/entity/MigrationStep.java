package com.pivotos.migration.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 迁移步骤表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("migration_step")
public class MigrationStep extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 所属任务 */
    private Long taskId;

    /** 步骤序号 */
    private Integer stepNo;

    /** 步骤名称 */
    private String name;

    /** 步骤类型：BACKEND/FRONTEND/DB */
    private String stepType;

    /** 关联模块 ID */
    private String moduleId;

    /** 关联模块名称 */
    private String moduleName;

    /** 步骤状态 */
    private Integer status;

    /** 本步骤执行前的 IR 快照（JSON） */
    private String irSnapshot;

    /** 本步骤生成的产物清单（JSON） */
    private String generatedArtifacts;

    /** 自测结果（JSON） */
    private String selfTestResult;

    /** 评审状态 */
    private Integer reviewStatus;

    /** 评审意见 */
    private String reviewComment;

    /** 执行前 Git commit hash */
    private String gitCommitHash;

    /** 错误信息 */
    private String errorMsg;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;
}
