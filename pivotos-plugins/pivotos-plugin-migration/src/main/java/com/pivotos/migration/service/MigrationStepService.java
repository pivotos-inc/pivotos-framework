package com.pivotos.migration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.migration.domain.entity.MigrationStep;

/**
 * 迁移步骤 Service。
 */
public interface MigrationStepService extends IService<MigrationStep> {

    /**
     * 执行迁移步骤：调用 AI 生成代码产物，简化自测后进入待评审状态。
     * 前置步骤状态：PENDING(0) 或 REJECTED(5)，后置：SELF_TEST_PASSED(2)。
     *
     * @param stepId 步骤 ID
     * @return 执行摘要（JSON 字符串）
     */
    String executeStep(Long stepId);

    /**
     * 人工评审步骤。
     * 前置步骤状态：SELF_TEST_PASSED(2)。
     * PASS → APPROVED(4) → COMPLETED(6)；REJECT → REJECTED(5)。
     *
     * @param stepId  步骤 ID
     * @param action  评审动作：PASS / REJECT
     * @param comment 评审意见（可空）
     */
    void reviewStep(Long stepId, String action, String comment);
}
