package com.pivotos.starter.tracing.config;

import com.pivotos.starter.tracing.propagation.XTraceIdPropagator;
import com.pivotos.starter.tracing.web.MdcContextFilter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开关语义断言：{@code pivotos.tracing.*} 各级开关必须真的能摘掉对应 Bean（P1-B8-2）。
 *
 * <p>为什么必须用 {@link WebApplicationContextRunner} 而不是 {@code ApplicationContextRunner}：
 * 自动配置上挂着 {@code @ConditionalOnWebApplication(type = SERVLET)}，
 * 用非 web 的 runner 跑，条件恒不满足 → 所有断言「都没有 bean」全绿，
 * 而「有 bean」的断言全红 —— 那是 runner 选错，不是代码有问题。
 *
 * <p>默认关（{@code enabled} 缺失）是本模块最重要的一条：
 * 引了依赖不等于开启（与 cloud alibaba「引入 ≠ 生效」同一口径），
 * 未配 Collector 就开启只会得到导出失败的日志噪音。
 */
class TracingAutoConfigurationSwitchTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TracingAutoConfiguration.class));

    @Test
    void disabled_by_default() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(XTraceIdPropagator.class);
            assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class);
            assertThat(context).doesNotHaveBean(MdcContextFilter.class);
            assertThat(context).doesNotHaveBean(Sampler.class);
        });
    }

    @Test
    void enabled_registers_all_pieces() {
        runner.withPropertyValues("pivotos.tracing.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(XTraceIdPropagator.class);
            assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void otlp_can_be_turned_off_alone() {
        runner.withPropertyValues(
            "pivotos.tracing.enabled=true",
            "pivotos.tracing.otlp.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(XTraceIdPropagator.class)
                .as("关导出不应连带关掉桥接");
            assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class);
        });
    }

    @Test
    void propagation_and_mdc_can_be_turned_off_alone() {
        runner.withPropertyValues(
            "pivotos.tracing.enabled=true",
            "pivotos.tracing.propagation.enabled=false",
            "pivotos.tracing.mdc.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(XTraceIdPropagator.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class)
                .as("关桥接/关 MDC 不应连带关掉导出");
        });
    }

    @Test
    void sampler_must_be_primary_or_the_whole_app_fails_to_start() {
        // 真机踩到（2026-10-12）：Boot 自带 otelSampler，本 Bean 若不是 @Primary，
        // otelSdkTracerProvider 注入处会报 "expected single matching bean but found 2:
        // otelSampler,tracingSampler" —— 后果是**整个应用起不来**，不是「追踪不可用」这种降级。
        // 这条守卫看的是 BeanDefinition 的 primary 标志（光断言「有这个 bean」盖不住这个故障）。
        runner.withPropertyValues(
            "pivotos.tracing.enabled=true",
            "pivotos.tracing.sampling-probability=0.1").run(context -> {
            assertThat(context).hasSingleBean(Sampler.class);
            org.springframework.beans.factory.config.BeanDefinition definition =
                ((org.springframework.context.ConfigurableApplicationContext) context.getSourceApplicationContext())
                    .getBeanFactory().getBeanDefinition("tracingSampler");
            assertThat(definition.isPrimary()).as("tracingSampler 必须是 @Primary").isTrue();
        });
    }

    @Test
    void sampler_registered_only_when_probability_is_configured() {
        runner.withPropertyValues("pivotos.tracing.enabled=true").run(context ->
            assertThat(context).doesNotHaveBean(Sampler.class));

        runner.withPropertyValues(
            "pivotos.tracing.enabled=true",
            "pivotos.tracing.sampling-probability=0.1").run(context ->
            assertThat(context).hasSingleBean(Sampler.class));
    }

    @Test
    void properties_bind_to_bean_values() {
        runner.withPropertyValues(
            "pivotos.tracing.enabled=true",
            "pivotos.tracing.otlp.endpoint=http://127.0.0.1:14318/v1/traces",
            "pivotos.tracing.otlp.timeout-ms=1234").run(context -> {
            TracingProperties properties = context.getBean(TracingProperties.class);
            assertThat(properties.getOtlp().getEndpoint()).isEqualTo("http://127.0.0.1:14318/v1/traces");
            assertThat(properties.getOtlp().getTimeoutMs()).isEqualTo(1234L);
        });
    }

    @Test
    void mdc_filter_order_is_after_login_and_tenant_filters() {
        // LoginContextFilter = HIGHEST_PRECEDENCE + 20，TenantContextFilter = +30，
        // 本过滤器必须更晚（+40），否则读不到 ScopedValue 绑定的身份（静默失效，不报错）
        assertThat(TracingAutoConfiguration.MDC_FILTER_ORDER)
            .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 30);
    }
}
