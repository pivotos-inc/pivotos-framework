package com.pivotos.starter.metrics.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BusinessMetrics} 单测（V3.1-F · P1-B8-1）。
 *
 * <p>最要紧的一条是 <b>no-op 形态必须零行为且零异常</b>：业务代码（检索模板 / AI 计量装饰器）
 * 无条件调用本类，一旦 no-op 形态抛异常，「关掉指标开关」就等于「打挂业务」，
 * 那这个开关就成了反向杀伤开关。
 *
 * @author PivotOS Team
 */
class BusinessMetricsTest {

    @Test
    void noOp形态零行为且不抛异常() {
        BusinessMetrics metrics = new BusinessMetrics(null);
        assertFalse(metrics.active(), "registry 为 null 时必须是不活跃形态");
        assertNull(metrics.registry());
        assertDoesNotThrow(() -> {
            metrics.recordSearch("simple", "search", true, 3L);
            metrics.recordAiCall("openai", "gpt-4o", false, 7L);
            metrics.recordAiTokens("openai", "gpt-4o", 10, 20);
            metrics.recordSlowRequest("GET", "/system/user/{id}", 1200L);
        }, "no-op 形态下任何埋点调用都不得抛异常");
    }

    @Test
    void 检索埋点按结果与操作分列() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);
        assertTrue(metrics.active());

        metrics.recordSearch("simple", "search", true, 12L);
        metrics.recordSearch("simple", "search", false, 5L);
        metrics.recordSearch("es-java", "count", true, 8L);

        assertEquals(1.0, counter(registry, "pivotos.search.calls", "simple", "search", "success"));
        assertEquals(1.0, counter(registry, "pivotos.search.calls", "simple", "search", "failure"));
        assertEquals(1.0, counter(registry, "pivotos.search.calls", "es-java", "count", "success"));
        // 不同 type/operation 是不同时间序列，不能互相串味
        assertNull(registry.find("pivotos.search.calls")
                .tag("type", "simple").tag("operation", "count").counter());
        assertEquals(2, registry.find("pivotos.search.latency").timers().size(),
                "耗时 Timer 按 type+operation 组合注册，此处应为 simple/search 与 es-java/count 两组");
    }

    @Test
    void ai埋点按供应商与模型分列且token按方向分别计数() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.recordAiCall("openai", "gpt-4o", true, 300L);
        metrics.recordAiTokens("openai", "gpt-4o", 100, 50);
        metrics.recordAiTokens("openai", "gpt-4o", 7, 9);

        assertEquals(1.0, registry.find("pivotos.ai.calls")
                .tag("provider", "openai").tag("model", "gpt-4o").tag("outcome", "success").counter().count());
        assertEquals(107.0, registry.find("pivotos.ai.tokens")
                .tag("provider", "openai").tag("model", "gpt-4o").tag("direction", "prompt").counter().count());
        assertEquals(59.0, registry.find("pivotos.ai.tokens")
                .tag("provider", "openai").tag("model", "gpt-4o").tag("direction", "completion").counter().count());
    }

    @Test
    void 空值与负数安全() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.recordSearch(null, "search", true, 1L);
        metrics.recordAiCall("openai", "  ", true, 1L);
        metrics.recordAiTokens("openai", "gpt-4o", -5, -1);

        assertEquals(1.0, counter(registry, "pivotos.search.calls", "unknown", "search", "success"));
        assertEquals(1.0, registry.find("pivotos.ai.calls")
                .tag("model", "unknown").counter().count(), "空白 model 必须归为 unknown");
        // 负数 token 表示「未计量」，按 0 计，不能把 counter 拉成负数
        assertEquals(0.0, registry.find("pivotos.ai.tokens")
                .tag("direction", "prompt").counter().count());
    }

    @Test
    void 慢接口埋点按方法与模板分列() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.recordSlowRequest("GET", "/system/user/{id}", 1500L);
        metrics.recordSlowRequest("POST", "/system/user", 2000L);

        assertEquals(1.0, registry.find("pivotos.http.slow")
                .tag("method", "GET").tag("pattern", "/system/user/{id}").counter().count());
        assertEquals(1.0, registry.find("pivotos.http.slow")
                .tag("method", "POST").tag("pattern", "/system/user").counter().count());
    }

    private static double counter(SimpleMeterRegistry registry, String name,
                                  String type, String operation, String outcome) {
        Counter counter = registry.find(name)
                .tag("type", type).tag("operation", operation).tag("outcome", outcome).counter();
        return counter == null ? -1.0 : counter.count();
    }
}
