package com.pivotos.starter.metrics.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.TimeUnit;

/**
 * 业务埋点门面（V3.1-F · P1-B8-1）：检索 / AI / 慢接口三类自研指标的**唯一**出口。
 *
 * <h3>为什么要有 no-op 形态</h3>
 * 总开关 {@code pivotos.metrics.enabled=false} 时，本类的实例仍然会被装配（由
 * {@code MetricsFallbackAutoConfiguration} 提供 registry == null 的实例），所有方法直接返回。
 * 这样业务代码（{@code SearchTemplateImpl} / {@code MeteredChatModel}）可以无条件注入本类，
 * 不必到处写 {@code ObjectProvider} 判空——否则「关掉指标」会让业务启动失败，
 * 一个可观测开关不该有这种杀伤力。
 *
 * <h3>tag 基数纪律</h3>
 * 所有 tag 取值必须是**有限集**（实现类型 / 供应商 / 模型名 / Spring 的 best-matching pattern）。
 * 绝不要把 userId、tenantId、文档 id、原始 URI 当 tag：Prometheus 每个 tag 组合都是一条独立时间序列，
 * 高基数是把自己打挂的最快方式。需要按用户/租户分析请走日志与 {@code sys_oper_log}。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · P1-B8-1）
 */
public class BusinessMetrics {

    /** 检索：调用次数 */
    public static final String SEARCH_CALLS = "pivotos.search.calls";
    /** 检索：耗时 */
    public static final String SEARCH_LATENCY = "pivotos.search.latency";
    /** AI：调用次数 */
    public static final String AI_CALLS = "pivotos.ai.calls";
    /** AI：token 用量（按方向分） */
    public static final String AI_TOKENS = "pivotos.ai.tokens";
    /** 慢接口：命中次数 */
    public static final String HTTP_SLOW = "pivotos.http.slow";

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 是否为 no-op 形态（总开关关闭时） */
    public boolean active() {
        return registry != null;
    }

    /**
     * 记录一次检索调用。
     *
     * @param type      检索实现（simple / es-java / easy-es），有限集
     * @param operation 操作（search / count / index / scored 等），有限集
     * @param success   是否成功
     * @param elapsedMs 耗时（毫秒）
     */
    public void recordSearch(String type, String operation, boolean success, long elapsedMs) {
        if (registry == null) {
            return;
        }
        Counter.builder(SEARCH_CALLS)
                .tag("type", nullToUnknown(type))
                .tag("operation", nullToUnknown(operation))
                .tag("outcome", success ? "success" : "failure")
                .register(registry)
                .increment();
        registry.timer(SEARCH_LATENCY, "type", nullToUnknown(type), "operation", nullToUnknown(operation))
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一次 AI（LLM）调用。
     *
     * @param provider  供应商编码，有限集
     * @param model     模型名，有限集
     * @param success   是否成功
     * @param elapsedMs 耗时（毫秒）
     */
    public void recordAiCall(String provider, String model, boolean success, long elapsedMs) {
        if (registry == null) {
            return;
        }
        Counter.builder(AI_CALLS)
                .tag("provider", nullToUnknown(provider))
                .tag("model", nullToUnknown(model))
                .tag("outcome", success ? "success" : "failure")
                .register(registry)
                .increment();
        registry.timer("pivotos.ai.latency", "provider", nullToUnknown(provider), "model", nullToUnknown(model))
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一次 AI 调用的 token 用量。
     *
     * @param provider        供应商编码
     * @param model           模型名
     * @param promptTokens    提示 token（未计量记 0）
     * @param completionTokens 补全 token（未计量记 0）
     */
    public void recordAiTokens(String provider, String model, int promptTokens, int completionTokens) {
        if (registry == null) {
            return;
        }
        Counter.builder(AI_TOKENS)
                .tag("provider", nullToUnknown(provider))
                .tag("model", nullToUnknown(model))
                .tag("direction", "prompt")
                .register(registry)
                .increment(Math.max(0, promptTokens));
        Counter.builder(AI_TOKENS)
                .tag("provider", nullToUnknown(provider))
                .tag("model", nullToUnknown(model))
                .tag("direction", "completion")
                .register(registry)
                .increment(Math.max(0, completionTokens));
    }

    /**
     * 记录一次慢接口命中。
     *
     * @param method    HTTP 方法
     * @param pattern   Spring 匹配到的 URI 模板（如 {@code /system/user/{id}}）；
     *                  拿不到时调用方降级传原始 URI，调用方需自行保证不是高基数
     * @param elapsedMs 耗时（毫秒）
     */
    public void recordSlowRequest(String method, String pattern, long elapsedMs) {
        if (registry == null) {
            return;
        }
        Counter.builder(HTTP_SLOW)
                .tag("method", nullToUnknown(method))
                .tag("pattern", nullToUnknown(pattern))
                .register(registry)
                .increment();
        registry.timer("pivotos.http.slow.latency", "method", nullToUnknown(method), "pattern", nullToUnknown(pattern))
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    private static String nullToUnknown(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }

    /** 暴露底层 registry，供同一进程内需要直接取 Meter 的场景（测试与高级用法） */
    public MeterRegistry registry() {
        return registry;
    }
}
