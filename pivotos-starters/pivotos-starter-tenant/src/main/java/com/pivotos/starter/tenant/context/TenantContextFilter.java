package com.pivotos.starter.tenant.context;

import com.alibaba.fastjson2.JSON;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.tenant.config.properties.TenantProperties;
import com.pivotos.starter.tenant.resolver.TenantResolver;
import com.pivotos.starter.tenant.strategy.TenantStrategy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 租户上下文绑定过滤器：顺序紧随 auth 的 LoginContextFilter（+20）之后（+30）。
 * <p>流程：ignore-urls 命中 → 直接放行；否则 TenantResolver 解析租户，
 * 解析到 → strategy.apply → ScopedValue 绑定 TenantContext 包裹请求链 → finally strategy.clear；
 * 解析不到 → 默认放行（单租户行为），strict 模式 → 403 拒绝。
 * <p>登录链路增强由此兑现：不修改 auth 一行代码，每请求在 LoginContext 绑定后消费契约层登录态。
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    private final TenantProperties properties;
    private final TenantResolver tenantResolver;
    private final TenantStrategy tenantStrategy;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public TenantContextFilter(TenantProperties properties, TenantResolver tenantResolver, TenantStrategy tenantStrategy) {
        this.properties = properties;
        this.tenantResolver = tenantResolver;
        this.tenantStrategy = tenantStrategy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        String path = request.getRequestURI();
        if (isIgnored(path)) {
            doChain(request, response, filterChain);
            return;
        }
        Long tenantId = tenantResolver.resolve(request);
        if (tenantId == null) {
            if (properties.isStrict()) {
                reject(response);
                return;
            }
            doChain(request, response, filterChain);
            return;
        }
        ScopedValue.where(TenantContext.KEY, tenantId).run(() -> {
            tenantStrategy.apply(tenantId);
            try {
                doChain(request, response, filterChain);
            } finally {
                tenantStrategy.clear();
            }
        });
    }

    private boolean isIgnored(String path) {
        for (String pattern : properties.getIgnoreUrls()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getOutputStream().write(JSON.toJSONString(R.fail(1003, "缺少租户上下文"))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[PivotOS] 租户严格模式拒绝响应写入失败", e);
        }
    }

    private void doChain(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            throw new IllegalStateException("TenantContextFilter 请求链执行失败", e);
        }
    }
}
