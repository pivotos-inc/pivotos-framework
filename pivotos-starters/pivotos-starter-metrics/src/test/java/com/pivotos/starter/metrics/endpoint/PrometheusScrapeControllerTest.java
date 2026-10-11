package com.pivotos.starter.metrics.endpoint;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prometheus 抓取端点单测（V3.1-F · P1-B8-1）。
 *
 * <p>这里断言的是<b>输出真的是 Prometheus 文本格式</b>，而不是「返回了个字符串」：
 * Prometheus server 只认 {@code text/plain; version=0.0.4} 的 exposition format，
 * 端点返回 JSON 或空 200 都会让抓取静默失败（Prometheus 侧只会显示 target down，
 * 不会告诉你「格式不对」）。
 *
 * @author PivotOS Team
 */
class PrometheusScrapeControllerTest {

    @Test
    void 输出为标准Prometheus文本格式() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.counter("pivotos.scrape.probe", "tenant", "ignored").increment(3);

        String body = new PrometheusScrapeController(registry).scrape();

        assertNotNull(body);
        assertTrue(body.contains("pivotos_scrape_probe"), "点分名必须转成 Prometheus 的下划线名：\n" + body);
        assertTrue(body.contains("# TYPE pivotos_scrape_probe"), "必须带 # TYPE 行：\n" + body);
        assertTrue(body.contains("tenant="), "必须带 tag：\n" + body);
    }

    @Test
    void 无指标时返回空串而不是null() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        String body = new PrometheusScrapeController(registry).scrape();
        assertNotNull(body, "空抓取也必须返回非 null（否则 MVC 层会走 204/空响应路径）");
        assertEquals("", body, "一个指标都没有时 Prometheus 的空抓取就是空串");
    }
}
