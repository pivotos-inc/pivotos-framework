package com.pivotos.migration.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 迁移任务状态。
 */
@Getter
@AllArgsConstructor
public enum MigrationTaskStatus {

    CREATED(0, "已创建"),
    UPLOADING(1, "上传中"),
    UPLOADED(2, "已上传"),
    ANALYZING(3, "分析中"),
    ANALYZED(4, "已分析"),
    PLANNING(5, "计划中"),
    PLANNED(6, "已计划"),
    EXECUTING(7, "执行中"),
    EXECUTED(8, "已执行"),
    COMPLETED(9, "已完成"),
    FAILED(10, "失败"),
    ROLLING_BACK(11, "回滚中"),
    ROLLED_BACK(12, "已回滚");

    private final int code;
    private final String desc;

    public static MigrationTaskStatus of(int code) {
        for (MigrationTaskStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
