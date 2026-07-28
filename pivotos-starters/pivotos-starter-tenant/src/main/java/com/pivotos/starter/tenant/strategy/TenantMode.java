package com.pivotos.starter.tenant.strategy;

/**
 * 多租户隔离模式
 */
public enum TenantMode {

    /** 字段隔离：共享库共享表，MP 拦截器自动追加 tenant_id 条件（中小规模首选） */
    COLUMN,

    /** Schema 隔离：共享实例独立 schema，dynamic-datasource 按租户路由 */
    SCHEMA,

    /** 数据源隔离：独立数据源（可跨实例），dynamic-datasource 按租户路由 */
    DATASOURCE
}
