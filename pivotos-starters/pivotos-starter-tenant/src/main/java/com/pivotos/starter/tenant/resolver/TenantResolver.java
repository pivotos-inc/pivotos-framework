package com.pivotos.starter.tenant.resolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 租户解析器 SPI（登录链路增强的扩展点）。
 * <p>默认实现 {@link DefaultTenantResolver} 按"LoginUser.tenantId → 请求头"解析；
 * 业务侧（如用户-租户关系表）注册自己的 TenantResolver Bean 即可整体替换
 * （{@code @ConditionalOnMissingBean} 守护），无需改动本 Starter 与 auth。
 */
public interface TenantResolver {

    /**
     * 从当前请求/登录态解析租户 ID。
     *
     * @param request 当前请求
     * @return 租户 ID；解析不到返回 null（null = 单租户行为，全链路放行）
     */
    Long resolve(HttpServletRequest request);
}
