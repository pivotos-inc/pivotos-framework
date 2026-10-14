package com.pivotos.starter.tracing.propagation;

import com.pivotos.starter.core.context.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link XTraceIdPropagator} 的行为断言（纯计算层，零外部资源）。
 *
 * <p>重点在「桥接」二字：入站头里的 traceId 必须原样成为 span 的 traceId，
 * 一个字符都不能变 —— 变了就等于没桥（日志里的 ID 在 Jaeger 里搜不到）。
 */
class XTraceIdPropagatorTest {

    private static final String TRACE_ID = "9f3c1b2a4d5e6f708192a3b4c5d6e7f8";

    private final XTraceIdPropagator propagator = new XTraceIdPropagator();

    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    @Test
    void fields_declares_both_headers() {
        // traceparent 必须一并声明：下游靠它建真实父子层级（只声明自有头时它永远不会被传播）
        assertThat(propagator.fields())
            .containsExactlyInAnyOrder("X-Trace-Id", "traceparent");
    }

    // ---------------------------------------------------------------- 双头对齐（跨服务父子层级）

    @Test
    void inbound_traceparent_gives_real_parent_span_id() {
        // 跨服务父子层级的硬判据：父 spanId 必须等于上游 traceparent 里的那个，不能是合成值
        Map<String, String> carrier = Map.of("traceparent",
            "00-" + TRACE_ID + "-0000000000000042-01");

        SpanContext parent = Span.fromContext(propagator.extract(Context.root(), carrier, GETTER))
            .getSpanContext();

        assertThat(parent.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(parent.getSpanId()).as("父 spanId 必须取 traceparent 的真实值（这是本轮修的重点）")
            .isEqualTo("0000000000000042");
        assertThat(parent.isValid()).isTrue();
    }

    @Test
    void both_headers_agree_keeps_real_parent_span_id() {
        Map<String, String> carrier = Map.of(
            "traceparent", "00-" + TRACE_ID + "-0000000000000042-01",
            XTraceIdPropagator.HEADER, TRACE_ID);

        SpanContext parent = Span.fromContext(propagator.extract(Context.root(), carrier, GETTER))
            .getSpanContext();

        assertThat(parent.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(parent.getSpanId()).isEqualTo("0000000000000042");
    }

    @Test
    void conflicting_headers_align_trace_id_but_keep_real_span_id() {
        // 上游只传了 X-Trace-Id、中间层自己开了新 trace 时会撞上：
        // traceId 以自有头为准（保证日志 / 响应头 / Jaeger 三处同 ID），
        // spanId 仍取 traceparent 的真实值（不牺牲父子层级）
        Map<String, String> carrier = Map.of(
            "traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-0000000000000042-01",
            XTraceIdPropagator.HEADER, TRACE_ID);

        SpanContext parent = Span.fromContext(propagator.extract(Context.root(), carrier, GETTER))
            .getSpanContext();

        assertThat(parent.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(parent.getSpanId()).isEqualTo("0000000000000042");
    }

    @Test
    void inject_writes_traceparent_as_well_as_project_header() {
        SpanContext spanContext = SpanContext.create(TRACE_ID, "0000000000000001",
            TraceFlags.getSampled(), TraceState.getDefault());
        Context context = Context.root().with(Span.wrap(spanContext));

        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);

        assertThat(carrier).containsEntry(XTraceIdPropagator.HEADER, TRACE_ID);
        assertThat(carrier.get("traceparent"))
            .as("下游要能拿到 traceparent 才能建真实父子")
            .isEqualTo("00-" + TRACE_ID + "-0000000000000001-01");
    }

    @Test
    void malformed_traceparent_falls_back_without_throwing() {
        // 头是外部可控输入：traceparent 写错不该让请求失败，也不该污染自有头的桥接
        Map<String, String> carrier = Map.of(
            "traceparent", "not-a-traceparent",
            XTraceIdPropagator.HEADER, TRACE_ID);

        SpanContext parent = Span.fromContext(propagator.extract(Context.root(), carrier, GETTER))
            .getSpanContext();

        assertThat(parent.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(parent.isValid()).isTrue();
    }

    @Test
    void prefers_bound_trace_context_when_request_has_no_header() {
        // 覆盖「普通请求」这一大多数场景：入口 TraceIdFilter 生成了 id（写进 MDC / 响应头），
        // 但请求头里没有 X-Trace-Id —— 此时必须沿用入口那个 id，否则日志与 Jaeger 各说各话
        Map<String, String> carrier = new HashMap<>();

        Context extracted = ScopedValue.where(TraceContext.KEY, TRACE_ID)
            .call(() -> propagator.extract(Context.root(), carrier, GETTER));

        assertThat(Span.fromContext(extracted).getSpanContext().getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void bound_trace_context_wins_over_conflicting_header() {
        // MDC / 响应头用的是 TraceContext 的值，故以它为准，保证「日志 / 响应头 / Jaeger 三处同 ID」
        Map<String, String> carrier = Map.of(XTraceIdPropagator.HEADER, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        Context extracted = ScopedValue.where(TraceContext.KEY, TRACE_ID)
            .call(() -> propagator.extract(Context.root(), carrier, GETTER));

        assertThat(Span.fromContext(extracted).getSpanContext().getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void extract_adopts_incoming_trace_id_as_parent() {
        Map<String, String> carrier = Map.of(XTraceIdPropagator.HEADER, TRACE_ID);

        Context extracted = propagator.extract(Context.root(), carrier, GETTER);

        SpanContext parent = Span.fromContext(extracted).getSpanContext();
        assertThat(parent.getTraceId()).as("桥接后 span 的 traceId 必须等于入站头").isEqualTo(TRACE_ID);
        assertThat(parent.getSpanId()).as("spanId 必须合法（非全 0，否则整条上下文判为 invalid）")
            .isNotEqualTo("0000000000000000");
        assertThat(parent.isValid()).isTrue();
    }

    @Test
    void extract_ignores_illegal_or_missing_values() {
        // 长度不足 / 非十六进制 / 全 0 / 空 / 未传 —— 全部回退（Context 不变），绝不抛异常
        assertThat(Span.fromContext(propagator.extract(Context.root(),
            Map.of(XTraceIdPropagator.HEADER, "abc"), GETTER)).getSpanContext().isValid()).isFalse();
        assertThat(Span.fromContext(propagator.extract(Context.root(),
            Map.of(XTraceIdPropagator.HEADER, "z".repeat(32)), GETTER)).getSpanContext().isValid()).isFalse();
        assertThat(Span.fromContext(propagator.extract(Context.root(),
            Map.of(XTraceIdPropagator.HEADER, "0".repeat(32)), GETTER)).getSpanContext().isValid()).isFalse();
        assertThat(propagator.extract(Context.root(), new HashMap<>(), GETTER)).isSameAs(Context.root());
    }

    @Test
    void inject_writes_current_trace_id_back_to_header() {
        SpanContext spanContext = SpanContext.create(TRACE_ID, "0000000000000001",
            TraceFlags.getSampled(), TraceState.getDefault());
        Context context = Context.root().with(Span.wrap(spanContext));

        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, SETTER);

        assertThat(carrier).containsEntry(XTraceIdPropagator.HEADER, TRACE_ID);
    }

    @Test
    void inject_writes_nothing_when_no_valid_span() {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(Context.root(), carrier, SETTER);
        assertThat(carrier).isEmpty();
    }

    @Test
    void round_trip_keeps_trace_id_stable() {
        Map<String, String> inbound = Map.of(XTraceIdPropagator.HEADER, TRACE_ID);
        Context extracted = propagator.extract(Context.root(), inbound, GETTER);

        Map<String, String> outbound = new HashMap<>();
        propagator.inject(extracted, outbound, SETTER);

        assertThat(outbound.get(XTraceIdPropagator.HEADER))
            .as("入站 → 出站必须同一个 traceId（否则跨服务就断了）").isEqualTo(TRACE_ID);
    }
}
