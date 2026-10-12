package com.pivotos.starter.tracing.propagation;

import com.pivotos.common.core.constant.CommonConstants;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

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
 * <h3>为什么 extract 要自己造一个 spanId</h3>
 * {@code X-Trace-Id} 只带 traceId、不带 spanId（历史设计如此，改头格式会波及前端）。
 * 而 OTel 的 {@code SpanContext} 要求 spanId 合法（16 位十六进制，全 0 视为无效），
 * 传 {@link SpanId#getInvalid()} 会让整条上下文被判定为 invalid、parent 直接丢失 ——
 * 表现为「加了传播器但 Jaeger 里 traceId 还是新的」，极难归因。
 * 因此这里补一个随机 spanId 当作父 spanId：traceId 对齐（这是桥接的目的），
 * 父 spanId 是合成值（跨服务的父子层级因此不严谨，这一点如实记录，不做粉饰）。
 *
 * <h3>非法值一律忽略</h3>
 * 头是外部可控输入：非 32 位十六进制（含全 0）一律当作没传，回退到 OTel 自己的生成逻辑，
 * 绝不抛异常 —— 链路头写错不该让请求失败。
 */
public class XTraceIdPropagator implements TextMapPropagator {

    /** 与 {@code TraceIdFilter} 共用同一个头常量，避免两处字面量漂移 */
    public static final String HEADER = CommonConstants.TRACE_ID_HEADER;

    private static final int TRACE_ID_HEX_LEN = 32;
    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

    @Override
    public Collection<String> fields() {
        return List.of(HEADER);
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        SpanContext spanContext = Span.fromContext(context).getSpanContext();
        if (!spanContext.isValid()) {
            return;
        }
        setter.set(carrier, HEADER, spanContext.getTraceId());
    }

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        String raw = getter.get(carrier, HEADER);
        String traceId = normalize(raw);
        if (traceId == null) {
            return context;
        }
        SpanContext parent = SpanContext.create(traceId, randomSpanId(),
            TraceFlags.getSampled(), TraceState.getDefault());
        return context.with(Span.wrap(parent));
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
