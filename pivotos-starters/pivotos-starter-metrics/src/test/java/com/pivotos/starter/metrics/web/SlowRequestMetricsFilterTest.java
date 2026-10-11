package com.pivotos.starter.metrics.web;

import com.pivotos.starter.metrics.support.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 慢接口 Filter 单测（V3.1-F · P1-B8-1）。
 *
 * <p>核心是防两类假绿：
 * <ol>
 *   <li><b>tag 基数爆炸</b>：用 {@code request.getRequestURI()}（含 id）当 tag 会把 Prometheus 打挂，
 *       因此必须优先取 {@code bestMatchingPattern}（URI 模板）。本测试显式断言「拿到的是模板不是实际 URI」；</li>
 *   <li><b>异常路径漏记</b>：请求抛异常时「慢」这个事实照样成立，finally 必须照记，
 *       否则最慢的那批请求（往往是崩掉的那些）反而不出现在指标里。</li>
 * </ol>
 *
 * @author PivotOS Team
 */
class SlowRequestMetricsFilterTest {

    @Test
    void 超阈值计数_且tag取URI模板而非实际URI() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SlowRequestMetricsFilter filter = new SlowRequestMetricsFilter(new BusinessMetrics(registry), 0L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/12345");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/system/user/{id}");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1.0, registry.find("pivotos.http.slow")
                .tag("method", "GET").tag("pattern", "/system/user/{id}").counter().count());
        assertNull(registry.find("pivotos.http.slow").tag("pattern", "/system/user/12345").counter(),
                "绝不能用含 id 的实际 URI 当 tag（高基数会把 Prometheus 打挂）");
    }

    @Test
    void 未匹配到handler时降级用原始URI() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SlowRequestMetricsFilter filter = new SlowRequestMetricsFilter(new BusinessMetrics(registry), 0L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/static/app.js");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1.0, registry.find("pivotos.http.slow")
                .tag("pattern", "/static/app.js").counter().count(),
                "拿不到 bestMatchingPattern（404/静态资源）时降级用原始 URI");
    }

    @Test
    void 未超阈值不计数() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SlowRequestMetricsFilter filter =
                new SlowRequestMetricsFilter(new BusinessMetrics(registry), 999_999L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/1");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/system/user/{id}");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(registry.find("pivotos.http.slow").counter(), "快请求不应产生任何慢请求时间序列");
    }

    @Test
    void 请求抛异常时仍然计数且异常继续上抛() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SlowRequestMetricsFilter filter = new SlowRequestMetricsFilter(new BusinessMetrics(registry), 0L);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai/chat");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/ai/chat");
        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    throw new IllegalStateException("模拟业务异常");
                }));

        assertEquals(1.0, registry.find("pivotos.http.slow")
                .tag("method", "POST").tag("pattern", "/ai/chat").counter().count(),
                "抛异常的请求往往正是最慢的那批，finally 必须照记");
    }

    @Test
    void noOp埋点下Filter不抛异常() {
        SlowRequestMetricsFilter filter = new SlowRequestMetricsFilter(new BusinessMetrics(null), 0L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/any");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/any");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain()));
    }
}
