package com.pivotos.migration.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 迁移步骤状态。
 */
@Getter
@AllArgsConstructor
public enum MigrationStepStatus {

    PENDING(0, "待执行"),
    EXECUTING(1, "执行中"),
    SELF_TEST_PASSED(2, "自测通过"),
    REVIEW_PENDING(3, "评审中"),
    APPROVED(4, "已通过"),
    REJECTED(5, "已驳回"),
    COMPLETED(6, "已完成"),
    FAILED(7, "失败"),
    ROLLING_BACK(8, "回滚中"),
    ROLLED_BACK(9, "已回滚");

    private final int code;
    private final String desc;

    public static MigrationStepStatus of(int code) {
        for (MigrationStepStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
