package com.pivotos.starter.tenant.strategy;

/**
 * 租户隔离策略（Strategy 模式）：请求进入时建立隔离环境，结束时清理。
 * <p>实现按 {@code pivotos.tenant.mode} 三选一装配：
 * COLUMN（MP 行级过滤，apply/clear 为 no-op）、
 * SCHEMA / DATASOURCE（dynamic-datasource 路由切换）。
 */
public interface TenantStrategy {

    /**
     * 本策略对应的隔离模式
     */
    TenantMode mode();

    /**
     * 请求链进入前调用（在 TenantContext 绑定 scope 内）。
     * COLUMN 为 no-op；SCHEMA/DATASOURCE 将租户映射的数据源 key 压入路由上下文。
     *
     * @param tenantId 当前租户 ID（非 null）
     */
    void apply(Long tenantId);

    /**
     * 请求链结束后清理（在 TenantContext 绑定 scope 内，可读取 TenantContext）。
     * 必须与 apply 成对：apply 压栈的路由 key 在此出栈。
     */
    void clear();
}
