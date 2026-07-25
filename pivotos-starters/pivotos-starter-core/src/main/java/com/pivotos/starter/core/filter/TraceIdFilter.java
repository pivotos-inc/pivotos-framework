package com.pivotos.starter.core.filter;

import com.pivotos.common.core.constant.CommonConstants;
import com.pivotos.starter.core.context.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器：请求入口生成/继承 TraceId，
 * ScopedValue 绑定包裹整条请求链，同步注入 MDC 供日志输出。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 键名，logback pattern 中用 %X{traceId} 输出 */
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        String traceId = request.getHeader(CommonConstants.TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String finalTraceId = traceId;
        ScopedValue.where(TraceContext.KEY, finalTraceId).run(() -> {
            MDC.put(MDC_KEY, finalTraceId);
            try {
                response.setHeader(CommonConstants.TRACE_ID_HEADER, finalTraceId);
                filterChain.doFilter(request, response);
            } catch (IOException | ServletException e) {
                throw new IllegalStateException("TraceIdFilter 请求链执行失败", e);
            } finally {
                MDC.remove(MDC_KEY);
            }
        });
    }
}
