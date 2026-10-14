package com.pivotos.starter.tracing.web;

import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MDC 补全过滤器：把 {@code userId / username / tenantId} 写进 MDC（P1-B8-2）。
 *
 * <h3>{@code traceId} 不归本过滤器管</h3>
 * {@code traceId} 由 starter-core 的 {@code TraceIdFilter} 写入（v2 既有能力，本轮不改），
 * 本过滤器只补「身份维度」——排查一次慢请求时，光有 traceId 不够，
 * 「是哪个用户、哪个租户触发的」才是定位多租户问题的关键，而这两个值此前只在业务代码里、
 * 从未进日志上下文。
 *
 * <h3>为什么必须晚于 LoginContextFilter / TenantContextFilter 执行</h3>
 * 两个上下文都用 {@code ScopedValue} 绑定（{@code LoginContextFilter} order =
 * {@code HIGHEST_PRECEDENCE + 20}，{@code TenantContextFilter} = {@code +30}），
 * ScopedValue **只在绑定时的调用栈内可见**。本过滤器 order = {@code +40}，
 * 位于二者的 {@code run()} 内部，因此读得到；放到它们之前则恒为空且不报错（静默失效）。
 *
 * <h3>为什么 finally 里只清理自己写的三个键</h3>
 * 容器线程复用：不清理会把上一个请求的身份带到下一个请求（串号，属于安全事故）。
 * 但**不能**顺手清 {@code traceId} —— 那是 {@code TraceIdFilter} 的键，
 * 越权清理会让它的清理逻辑变成半成品，且两个过滤器职责纠缠。
 */
public class MdcContextFilter extends OncePerRequestFilter {

    public static final String KEY_USER_ID = "userId";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_TENANT_ID = "tenantId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (LoginContext.isLogin()) {
                Long userId = LoginContext.getUserId();
                if (userId != null) {
                    MDC.put(KEY_USER_ID, String.valueOf(userId));
                }
                String username = LoginContext.getUsername();
                if (username != null && !username.isBlank()) {
                    MDC.put(KEY_USERNAME, username);
                }
            }
            if (TenantContext.isBound()) {
                Long tenantId = TenantContext.get();
                if (tenantId != null) {
                    MDC.put(KEY_TENANT_ID, String.valueOf(tenantId));
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(KEY_USER_ID);
            MDC.remove(KEY_USERNAME);
            MDC.remove(KEY_TENANT_ID);
        }
    }
}
