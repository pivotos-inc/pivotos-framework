package com.pivotos.starter.cloud.api.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 入站上下文恢复过滤器：把上游传来的租户/身份头恢复为本进程上下文。
 *
 * <p><b>默认只做一件事：什么都不做</b>——两个恢复开关默认 false，
 * 且即便打开也要求请求通过内部调用校验（{@link CloudHeaders#INTERNAL_TOKEN}）。
 * 链路 ID 不由本过滤器处理：既有 {@code TraceIdFilter} 已实现「有则继承、无则生成」，
 * 跨进程 trace 天然延续，此处重复绑定只会把上游值覆盖成同值，无意义。
 *
 * <p>顺序：注册在 TraceIdFilter 之后、TenantContextFilter 之前——
 * 先恢复内部调用带来的租户，TenantContextFilter 再按常规解析逻辑接管单租户/严格模式判定。
 */
public class CloudContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CloudContextFilter.class);

    private final CloudContextProperties properties;

    public CloudContextFilter(CloudContextProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        CloudContext inbound = CloudContextCodec.fromRequest(request);
        boolean internal = CloudContextCodec.isInternalCall(request, properties.getInternalToken());

        boolean restoreTenant = properties.isRestoreTenant() && internal;
        boolean restoreLogin = properties.isRestoreLogin() && internal;

        CloudContext effective = new CloudContext(
            restoreTenant ? inbound.getTenantId() : null,
            restoreLogin ? inbound.getUserId() : null,
            restoreLogin ? inbound.getUsername() : null,
            restoreLogin ? fallbackAccountType(inbound) : null,
            null);

        if (effective.isEmpty()) {
            doChain(request, response, chain);
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("[CLOUD][context] 恢复入站上下文：{}（internal={}）", effective, internal);
        }
        CloudContextCodec.runWith(effective, () -> doChain(request, response, chain));
    }

    private String fallbackAccountType(CloudContext inbound) {
        String accountType = inbound.getAccountType();
        return accountType == null ? properties.getDefaultAccountType() : accountType;
    }

    private void doChain(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            throw new IllegalStateException("CloudContextFilter 请求链执行失败", e);
        }
    }
}
