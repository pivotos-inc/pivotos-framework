package com.pivotos.starter.tracing.propagation;

import com.pivotos.common.core.constant.CommonConstants;
import com.pivotos.starter.core.context.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code X-Trace-Id} ⇄ OTel traceId 的桥接传播器（P1-B8-2）。
 *
 * <h3>为什么必须自己写这一个</h3>
 * 项目在 v2 时代就有了自己的链路头：{@code TraceIdFilter}（starter-core）在请求入口生成
 * {@code UUID} 去横线的 32 位十六进制并写进 MDC 与响应头，前端/网关一路都看这个值。
 * 而 Micrometer Tracing 默认只认 W3C 的 {@code traceparent} ——
 * **两套 traceId 互不相识**：日志里的 traceId 与 OTLP 导出的 traceId 是两个值，
 * 排障时「拿着日志里的 ID 去 Jaeger 搜，什么也搜不到」。
 *
 * <p>本传播器把二者对齐：入站用 {@code X-Trace-Id} 作为父 span 的 traceId，
 * 出站把当前 span 的 traceId 写回 {@code X-Trace-Id}。
 *
 * <h3>双头对齐（v3.2.0 补：跨服务父子层级）</h3>
 * 初版只认 {@code X-Trace-Id}，而它**只带 traceId、不带 spanId**（历史设计如此，改头格式会波及前端），
 * 于是父 spanId 只能合成一个随机值 —— Jaeger 里看得到同一个 traceId，
 * 但**父子层级是假的**（父 spanId 指向一个不存在的 span）。
 *
 * <p>取证（2026-10-13，**结论修正过一次**）：Boot 4 的
 * {@code OpenTelemetryTracingAutoConfiguration#otelContextPropagators} 是
 * {@code TextMapPropagator.composite(全部 TextMapPropagator bean)}，composite 的
 * {@code extract} 会**依次**把结果串起来（后写的 {@code context.with(...)} 覆盖先写的）。
 * Boot 自己注册了 W3C 传播器且排在本类之前 —— 实证：把本类的 {@code w3c.extract} 去掉后
 * 父子层级依然正确，说明 {@code traceparent} **一直有在被解析**。
 *
 * <p>所以真正的根因不是「traceparent 没参与」，而是**本类无条件用合成 spanId 覆盖了
 * W3C 已经解析好的真实 parent**：v3.1 的 extract 只要拿到 {@code X-Trace-Id} 就
 * {@code SpanContext.create(traceId, 随机spanId, ...)} 一把盖掉，真实父 spanId 当场丢失。
 *
 * <p>修法：先承认「入站 context 里可能已经有合法的父 span」，再决定要不要重写：
 * <ul>
 *   <li>有 {@code traceparent} → **父 spanId 直接取它的真实值**（父子层级真实）；</li>
 *   <li>两个头的 traceId 不一致时，以 {@code X-Trace-Id} 为准重写 traceId、
 *       但**保留 traceparent 的真实 spanId** —— 既守住「日志 / 响应头 / Jaeger 三处同 ID」，
 *       又不牺牲父子层级；</li>
 *   <li>没有 {@code traceparent}（老上游 / 浏览器直连）→ 退回合成 spanId，
 *       这是已知限制，如实记录。</li>
 * </ul>
 *
 * <h3>为什么这里还要自己再组合一份 {@link W3CTraceContextPropagator}</h3>
 * 既然 Boot 已经注册了 W3C，本类再调一次 {@code w3c.extract} 看起来是多余的
 * —— 实测去掉它也确实不影响结果。留着的理由是**不把正确性押在装配顺序上**：
 * composite 的先后由 {@code orderedStream()} 决定，一旦有人改了
 * {@code management.tracing.propagation.type}（比如切 b3）或新增别的传播器 bean，
 * 「谁排在前面」就会变；本类自带一份解析后，无论排在哪都能拿到真实父 spanId。
 * 代价只是多解析一次同一个头（幂等，无副作用）。
 *
 * <h3>非法值一律忽略</h3>
 * 头是外部可控输入：非 32 位十六进制（含全 0）一律当作没传，回退到 OTel 自己的生成逻辑，
 * 绝不抛异常 —— 链路头写错不该让请求失败。
 */
public class XTraceIdPropagator implements TextMapPropagator {

    /** 与 {@code TraceIdFilter} 共用同一个头常量，避免两处字面量漂移 */
    public static final String HEADER = CommonConstants.TRACE_ID_HEADER;

    /** W3C 标准头：跨服务的父子层级靠它传真实 spanId */
    public static final String TRACEPARENT_HEADER = "traceparent";

    private static final int TRACE_ID_HEX_LEN = 32;
    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

    /** 组合而非注册成第二个 bean：extract/inject 的先后顺序由本类固定，避免被装配顺序翻转 */
    private final W3CTraceContextPropagator w3c = W3CTraceContextPropagator.getInstance();

    @Override
    public Collection<String> fields() {
        return List.of(HEADER, TRACEPARENT_HEADER);
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        SpanContext spanContext = Span.fromContext(context).getSpanContext();
        if (!spanContext.isValid()) {
            return;
        }
        // 先写 traceparent（下游拿它建真实父子），再写自有头（前端/网关一路都在看它）
        w3c.inject(context, carrier, setter);
        setter.set(carrier, HEADER, spanContext.getTraceId());
    }

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        // ① 让 W3C 先解析 traceparent：有则拿到真实的父 spanId
        Context extracted = w3c.extract(context, carrier, getter);
        SpanContext fromW3c = Span.fromContext(extracted).getSpanContext();

        String traceId = resolveTraceId(carrier, getter);
        if (traceId == null) {
            // 没有自有链路头：traceparent 说了算（有则真实父子，无则原样交回 OTel 自己生成）
            return extracted;
        }
        if (fromW3c.isValid() && traceId.equals(fromW3c.getTraceId())) {
            // ② 两头一致 —— 直接采用，父 spanId 是真实值
            return extracted;
        }
        if (fromW3c.isValid()) {
            // ③ 两头 traceId 不一致：以自有头为准（保证三处同 ID），但保留 traceparent 的真实 spanId
            SpanContext aligned = SpanContext.create(traceId, fromW3c.getSpanId(),
                fromW3c.getTraceFlags(), fromW3c.getTraceState());
            return extracted.with(Span.wrap(aligned));
        }
        // ④ 只有 X-Trace-Id：只能合成父 spanId（已知限制，父子层级在这一支上是假的）
        SpanContext parent = SpanContext.create(traceId, randomSpanId(),
            TraceFlags.getSampled(), TraceState.getDefault());
        return extracted.with(Span.wrap(parent));
    }

    /**
     * 决定用哪个 traceId，优先级：
     * <ol>
     *   <li><b>请求入口已绑定的 {@link TraceContext}</b>（{@code TraceIdFilter} 写入）；
     *   <li>入站 {@code X-Trace-Id} 请求头；
     *   <li>都没有 → 返回 null，交给 OTel 自己生成。</li>
     * </ol>
     *
     * <p><b>为什么必须有第 1 条</b>（这条是本轮设计缺陷的修正）：
     * 只认请求头时，「入站没带 {@code X-Trace-Id}」的普通请求（浏览器直连、定时任务回调）
     * 会出现两个 traceId —— {@code TraceIdFilter} 生成的进了 MDC 与响应头，
     * OTel 又自己生成了一个进了 span。**日志里的 ID 在 Jaeger 里搜不到**，
     * 而且这种请求恰恰占绝大多数：桥接只在「上游传了头」时才成立等于没桥。
     * {@code TraceIdFilter} 的 order 是 {@code HIGHEST_PRECEDENCE}、且用 ScopedValue 绑定，
     * 到 Observation 创建 span 时（仍是同一请求线程）它必然可见，因此取它最准确。
     *
     * <p>两者不一致时以 {@link TraceContext} 为准：MDC、响应头用的都是它，
     * 以它为准才能保证「三处（日志 / 响应头 / Jaeger）同一个 ID」。
     */
    private <C> String resolveTraceId(C carrier, TextMapGetter<C> getter) {
        String bound = normalize(TraceContext.get());
        if (bound != null) {
            return bound;
        }
        if (getter == null || carrier == null) {
            return null;
        }
        return normalize(getter.get(carrier, HEADER));
    }

    /** 合法化：null / 非 32 位十六进制 / 全 0 一律返回 null（表示不桥接） */
    static String normalize(String raw) {
        if (raw == null || raw.length() != TRACE_ID_HEX_LEN) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return INVALID_TRACE_ID.equals(lower) ? null : lower;
    }

    /** 合成父 spanId：必须非零，否则 SpanContext 判为 invalid（见类注释） */
    private static String randomSpanId() {
        long value = ThreadLocalRandom.current().nextLong();
        if (value == 0L) {
            value = 1L;
        }
        return SpanId.fromLong(value);
    }
}
