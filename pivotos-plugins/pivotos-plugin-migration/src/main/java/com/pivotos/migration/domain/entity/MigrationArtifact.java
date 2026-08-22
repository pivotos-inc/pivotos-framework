package com.pivotos.migration.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 迁移产物表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("migration_artifact")
public class MigrationArtifact extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 所属任务 */
    private Long taskId;

    /** 所属步骤 */
    private Long stepId;

    /** 产物类型：JAVA/VUE/FLYWAY/OTHER */
    private String artifactType;

    /** 相对项目根目录的路径 */
    private String relativePath;

    /** 内容哈希 */
    private String contentHash;

    /** 原代码内容（用于对比） */
    private String originalContent;

    /** 生成的代码内容 */
    private String generatedContent;

    /** 是否已落盘 */
    private Boolean applied;
}
