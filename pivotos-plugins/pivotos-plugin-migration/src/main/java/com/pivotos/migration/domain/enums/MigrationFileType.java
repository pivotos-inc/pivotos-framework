package com.pivotos.migration.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 迁移源文件类型。
 */
@Getter
@AllArgsConstructor
public enum MigrationFileType {

    BACKEND("BACKEND", "后端源码"),
    FRONTEND("FRONTEND", "前端源码");

    private final String code;
    private final String desc;
}
