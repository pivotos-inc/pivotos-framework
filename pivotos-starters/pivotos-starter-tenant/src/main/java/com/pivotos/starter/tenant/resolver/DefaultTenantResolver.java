package com.pivotos.starter.tenant.resolver;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.tenant.config.properties.TenantProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 默认租户解析器，优先级链：
 * <ol>
 *   <li>{@code LoginUser.tenantId} —— 登录态已携带租户（LoginUser 预留字段，
 *   为后续"登录选租户"功能准备的载体）；</li>
 *   <li>请求头 {@code pivotos.tenant.header-name}（默认 X-Tenant-Id）；</li>
 *   <li>解析不到返回 null（单租户行为）。</li>
 * </ol>
 * 只读 common-api / starter-core 契约层，对 auth 内部零反向依赖（S14 设计评审 D2）。
 */
public class DefaultTenantResolver implements TenantResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantResolver.class);

    private final TenantProperties properties;

    public DefaultTenantResolver(TenantProperties properties) {
        this.properties = properties;
    }

    @Override
    public Long resolve(HttpServletRequest request) {
        LoginUser user = LoginContext.get();
        if (user != null && user.getTenantId() != null) {
            return user.getTenantId();
        }
        String header = request.getHeader(properties.getHeaderName());
        if (!StringUtils.hasText(header)) {
            return null;
        }
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException e) {
            log.warn("[PivotOS] 租户请求头 {} 值非法：{}", properties.getHeaderName(), header);
            return null;
        }
    }
}
