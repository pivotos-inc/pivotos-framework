package com.pivotos.starter.metrics.support;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 调用 span attribute（v3.2.0 · P1-B8-2 补链路侧）。
 *
 * <p>断言的不是「有没有调用某个方法」，而是**最终落在 span 上的键值对**：
 * provider / model / outcome 必须是 low cardinality（可当 tag），
 * token 数必须是 high cardinality（只进 span attribute，绝不进 Prometheus tag）。
 *
 * <p>为什么用自定义 {@link ObservationHandler} 捕获而不是断言某个 mock：
 * Observation 的语义就是「一路 handler 都能看到 context」，抓 context 才是终态证据；
 * 「调了 startAiCall」这种断言在 registry 为 null 时也能通过，属于假绿。
 */
class AiCallObservationTest {

    /** 捕获 handler：记录最后一次 stop 时 context 上的全部键值 */
    private static final class CapturingHandler implements ObservationHandler<Context> {

        private final Map<String, String> low = new LinkedHashMap<>();
        private final Map<String, String> high = new LinkedHashMap<>();
        private String name;
        private boolean errored;

        @Override
        public boolean supportsContext(Context context) {
            return true;
        }

        @Override
        public void onStop(Context context) {
            name = context.getName();
            for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                low.put(kv.getKey(), kv.getValue());
            }
            for (KeyValue kv : context.getHighCardinalityKeyValues()) {
                high.put(kv.getKey(), kv.getValue());
            }
            errored = context.getError() != null;
        }
    }

    private static ObservationRegistry registryWith(CapturingHandler handler) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        return registry;
    }

    @Test
    void 成功调用_span上带provider_model_outcome与token数() {
        CapturingHandler handler = new CapturingHandler();
        BusinessMetrics metrics = new BusinessMetrics(null, registryWith(handler));

        Observation obs = metrics.startAiCall("openai", "gpt-4o");
        metrics.stopAiCall(obs, true, 120, 34, null);

        assertEquals("pivotos.ai.call", handler.name, "span 名就是 Observation 名");
        assertEquals("openai", handler.low.get("ai.provider"));
        assertEquals("gpt-4o", handler.low.get("ai.model"));
        assertEquals("success", handler.low.get("ai.outcome"));
        assertEquals("120", handler.high.get("ai.prompt_tokens"));
        assertEquals("34", handler.high.get("ai.completion_tokens"));
        assertFalse(handler.errored);
    }

    @Test
    void 失败调用_outcome为failure且带error() {
        CapturingHandler handler = new CapturingHandler();
        BusinessMetrics metrics = new BusinessMetrics(null, registryWith(handler));

        Observation obs = metrics.startAiCall("openai", "gpt-4o");
        metrics.stopAiCall(obs, false, 0, 0, new IllegalStateException("boom"));

        assertEquals("failure", handler.low.get("ai.outcome"));
        assertTrue(handler.errored, "失败必须挂上 error，否则 span 上看不出是失败");
    }

    @Test
    void token数走highCardinality_绝不进Prometheus_tag() {
        // 高基数是「接了可观测之后把自己打挂」的经典形态：token 数每个请求都不同，
        // 一旦变成 tag，Prometheus 侧每个取值都是一条独立时间序列。
        CapturingHandler handler = new CapturingHandler();
        BusinessMetrics metrics = new BusinessMetrics(null, registryWith(handler));

        Observation obs = metrics.startAiCall("openai", "gpt-4o");
        metrics.stopAiCall(obs, true, 99887, 65432, null);

        assertTrue(handler.low.containsKey("ai.provider"), "provider/model 才是 tag 该有的维度");
        assertFalse(handler.low.containsKey("ai.prompt_tokens"),
            "prompt_tokens 绝不能进 low cardinality（否则会成为 Prometheus tag）");
        assertFalse(handler.low.containsKey("ai.completion_tokens"));
        assertEquals("99887", handler.high.get("ai.prompt_tokens"));
    }

    @Test
    void 未装配ObservationRegistry时返回noop且零异常() {
        BusinessMetrics metrics = new BusinessMetrics(null, null);
        assertFalse(metrics.observationActive());

        Observation obs = metrics.startAiCall("openai", "gpt-4o");
        assertTrue(obs.isNoop(), "没有链路门面时必须退化成 no-op，业务侧零感知");
        assertDoesNotThrow(() -> metrics.stopAiCall(obs, true, 1, 2, null));
        assertDoesNotThrow(() -> metrics.stopAiCall(null, true, 1, 2, null), "传 null 也要能兜住");
    }

    @Test
    void 未知值统一归一为unknown() {
        CapturingHandler handler = new CapturingHandler();
        BusinessMetrics metrics = new BusinessMetrics(null, registryWith(handler));

        Observation obs = metrics.startAiCall(null, "");
        metrics.stopAiCall(obs, true, 0, 0, null);

        assertEquals("unknown", handler.low.get("ai.provider"));
        assertEquals("unknown", handler.low.get("ai.model"));
        assertEquals("0", handler.high.get("ai.prompt_tokens"), "供应商未返回用量时记 0（口径：未计量）");
    }
}
