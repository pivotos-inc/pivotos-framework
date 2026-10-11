package com.pivotos.starter.metrics.web;

import com.pivotos.starter.metrics.support.BusinessMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 慢接口统计 Filter（V3.1-F · P1-B8-1）：耗时超过阈值的请求计一次数 + 记一次耗时。
 *
 * <h3>为什么还要自研，而不是直接用 http.server.requests</h3>
 * {@code http.server.requests}（Boot 自动提供）是全量请求的直方图，能算 P95 但要靠 PromQL 反推，
 * 且默认不按 URI 分桶之外的维度给「慢」这个语义。这里补一个**直白的**慢请求计数：
 * 运维不用写 PromQL 就能看到「今天哪些接口慢了几次」，与全量直方图互为补充，不替代。
 *
 * <h3>URI 取 best-matching pattern（关键）</h3>
 * 必须取 {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}（如 {@code /system/user/{id}}），
 * 不能用 {@code request.getRequestURI()}（含 id，会把 tag 基数打成无穷大）。
 * 该属性由 DispatcherServlet 在处理时写入，本 Filter 的 finally 在其返回之后执行，读得到。
 * 未匹配到 handler 的请求（404 / 静态资源）属性为 null，此时降级用原始 URI
 * —— 这类 URI 数量有限，可接受；若将来出现带随机串的静态路径，应改为直接跳过。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · P1-B8-1）
 */
public class SlowRequestMetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SlowRequestMetricsFilter.class);

    private final BusinessMetrics metrics;
    private final long thresholdMs;

    public SlowRequestMetricsFilter(BusinessMetrics metrics, long thresholdMs) {
        this.metrics = metrics;
        this.thresholdMs = thresholdMs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (elapsedMs >= thresholdMs) {
                String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                boolean matched = pattern != null;
                if (!matched) {
                    pattern = request.getRequestURI();
                }
                metrics.recordSlowRequest(request.getMethod(), pattern, elapsedMs);
                log.warn("[METRICS] 慢请求：{} {} ({}ms, matched={})",
                        request.getMethod(), pattern, elapsedMs, matched);
            }
        }
    }
}
