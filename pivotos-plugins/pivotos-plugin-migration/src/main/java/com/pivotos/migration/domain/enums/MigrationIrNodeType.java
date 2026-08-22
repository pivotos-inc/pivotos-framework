package com.pivotos.migration.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * IR 节点类型。
 */
@Getter
@AllArgsConstructor
public enum MigrationIrNodeType {

    PROJECT("PROJECT", "项目"),
    MODULE("MODULE", "模块"),
    TABLE("TABLE", "数据库表"),
    ENTITY("ENTITY", "数据实体"),
    ROUTE("ROUTE", "HTTP 接口路由"),
    SERVICE("SERVICE", "业务服务"),
    PAGE("PAGE", "前端页面"),
    COMPONENT("COMPONENT", "前端组件");

    private final String code;
    private final String desc;
}
