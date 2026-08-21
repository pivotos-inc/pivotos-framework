package com.pivotos.migration.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 迁移人工评审记录表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("migration_review")
public class MigrationReview extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 所属任务 */
    private Long taskId;

    /** 关联步骤（任务级评审可为 NULL） */
    private Long stepId;

    /** 评审人 ID */
    private Long reviewerId;

    /** 评审动作：PASS/REJECT/MODIFY */
    private String action;

    /** 评审意见 */
    private String comment;

    /** 修改内容（JSON） */
    private String modifications;
}
