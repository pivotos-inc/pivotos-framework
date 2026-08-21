package com.pivotos.migration.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 迁移日志级别。
 */
@Getter
@AllArgsConstructor
public enum MigrationLogLevel {

    DEBUG("DEBUG", "调试"),
    INFO("INFO", "信息"),
    WARN("WARN", "警告"),
    ERROR("ERROR", "错误");

    private final String code;
    private final String desc;
}
