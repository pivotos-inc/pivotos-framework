package com.pivotos.migration.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 迁移产物类型。
 */
@Getter
@AllArgsConstructor
public enum MigrationArtifactType {

    JAVA("JAVA", "Java 源码"),
    VUE("VUE", "Vue 页面"),
    FLYWAY("FLYWAY", "Flyway 迁移脚本"),
    OTHER("OTHER", "其他产物");

    private final String code;
    private final String desc;
}
