package com.pivotos.starter.tracing.config;

import com.pivotos.starter.tracing.propagation.XTraceIdPropagator;
import com.pivotos.starter.tracing.web.MdcContextFilter;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

import java.util.concurrent.TimeUnit;

/**
 * 链路追踪自动配置（v3.2.0 · P1-B8-2）。
 *
 * <h3>本模块刻意不重复造的东西</h3>
 * <ul>
 *   <li>{@code Tracer} / {@code ObservationHandler} / {@code SdkTracerProvider} —— 由
 *       Boot 的 {@code OpenTelemetryTracingAutoConfiguration} 提供（我们只需保证
 *       {@code io.micrometer.tracing.otel.bridge.OtelTracer} 在 classpath 上，
 *       即显式引 {@code micrometer-tracing-bridge-otel}，见 pom 注释）；</li>
 *   <li>W3C {@code traceparent} 传播 —— Boot 自带，本模块只**追加** {@code X-Trace-Id} 一项；</li>
 *   <li>{@code traceId} 生成与 MDC 写入 —— starter-core 的 {@code TraceIdFilter} 早已在做。</li>
 * </ul>
 * 本模块只补三件 Boot 管不到的事：{@code X-Trace-Id} 桥接、OTLP 导出器按本项目配置装配、
 * MDC 补 userId/tenantId。
 *
 * <h3>总开关关掉时的形态</h3>
 * {@code pivotos.tracing.enabled=false}（默认）→ 本类整体不装配，
 * 应用行为与加本模块之前**完全一致**（既有 TraceIdFilter 照常工作）。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({Tracer.class, OtlpHttpSpanExporter.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "pivotos.tracing", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TracingProperties.class)
public class TracingAutoConfiguration {

    /**
     * MDC 补全 Filter 的 order：必须 {@code >} LoginContextFilter(+20) 与 TenantContextFilter(+30)，
     * 否则读不到 ScopedValue 绑定的身份（详见 {@link MdcContextFilter} 类注释）。
     */
    public static final int MDC_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 40;

    @Bean
    @ConditionalOnProperty(prefix = "pivotos.tracing.propagation", name = "enabled", matchIfMissing = true)
    public XTraceIdPropagator xTraceIdPropagator() {
        log.info("[TRACING] X-Trace-Id 桥接传播器已注册：header={}", XTraceIdPropagator.HEADER);
        return new XTraceIdPropagator();
    }

    /**
     * OTLP 导出器：用本项目的 {@code pivotos.tracing.otlp.*} 装配，
     * 而不是要求运维去记 Boot 的 {@code management.opentelemetry.tracing.export.otlp.*}
     * （前缀长且含 opentelemetry 单词，写错的代价是「静默不导出」）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "pivotos.tracing.otlp", name = "enabled", matchIfMissing = true)
    public OtlpHttpSpanExporter otlpHttpSpanExporter(TracingProperties properties) {
        TracingProperties.Otlp otlp = properties.getOtlp();
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
            .setEndpoint(otlp.getEndpoint())
            .setTimeout(otlp.getTimeoutMs(), TimeUnit.MILLISECONDS)
            .build();
        log.info("[TRACING] OTLP 导出器已注册：endpoint={} timeoutMs={}",
            otlp.getEndpoint(), otlp.getTimeoutMs());
        return exporter;
    }

    /**
     * 采样器：只在 {@code sampling-probability} 显式配置时注册（1.0 时交给 Boot 默认，避免多余 Bean）。
     * 用 {@code parentBased} 是刻意的：父 span 已采样则子 span 必采，
     * 否则会出现「请求的前半段有 span、后半段没 span」的断链，比全采更难排查。
     *
     * <p><b>为什么必须 @Primary</b>（2026-10-12 真机踩到）：Boot 自己就有一个 {@code otelSampler}，
     * 本 Bean 一旦同时存在，`otelSdkTracerProvider` 注入处会直接
     * {@code No qualifying bean of type Sampler: expected single matching bean but found 2} ——
     * **整个应用起不来**（不是「追踪不可用」这种降级，是启动失败）。
     * 而 @ConditionalOnMissingBean 在这里靠不住：它取决于本自动配置与
     * {@code OpenTelemetryTracingAutoConfiguration} 的评估先后，换一次依赖顺序就可能翻转。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "pivotos.tracing", name = "sampling-probability", matchIfMissing = false)
    public Sampler tracingSampler(TracingProperties properties) {
        double probability = properties.getSamplingProbability();
        Sampler sampler = Sampler.parentBased(Sampler.traceIdRatioBased(probability));
        log.info("[TRACING] 采样器已注册：probability={}", probability);
        return sampler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "pivotos.tracing.mdc", name = "enabled", matchIfMissing = true)
    public FilterRegistrationBean<MdcContextFilter> mdcContextFilterRegistration() {
        FilterRegistrationBean<MdcContextFilter> registration =
            new FilterRegistrationBean<>(new MdcContextFilter());
        registration.setOrder(MDC_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        log.info("[TRACING] MDC 补全 Filter 已注册：order={} keys=[userId, username, tenantId]",
            MDC_FILTER_ORDER);
        return registration;
    }
}
