package com.pivotos.starter.metrics.config;

import com.pivotos.starter.metrics.endpoint.PrometheusScrapeController;
import com.pivotos.starter.metrics.support.BusinessMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标开关的自动配置单测（V3.1-F · P1-B8-1）。
 *
 * <p>断言的是<b>开关的真实后果</b>，而不是「配置读到了」：
 * <ul>
 *   <li>{@code enabled=false} → 埋点是 no-op，且端点与 Filter 都不注册（不是「注册了但不出数」）；</li>
 *   <li>{@code prometheus.enabled=false} → 端点不注册，但埋点仍然活跃（指标还在内存里）；</li>
 *   <li>classpath 上没有 MeterRegistry → 埋点降级 no-op，但<b>上下文照常起来</b>。</li>
 * </ul>
 * 第三条是最容易被漏掉的：把可观测开关接成「缺依赖就起不来」，等于给生产加了个新的启动单点。
 *
 * @author PivotOS Team
 */
class MetricsAutoConfigurationSwitchTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsInfrastructureAutoConfiguration.class,
                        MetricsNoOpAutoConfiguration.class))
                // Prometheus registry 由 Boot 的导出自动配置提供；这里手动给一个以隔离出「开关」这一个变量
                .withBean(PrometheusMeterRegistry.class, () -> new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    @Test
    void 默认装配_埋点激活_端点与慢接口Filter均注册() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(BusinessMetrics.class);
            assertThat(context.getBean(BusinessMetrics.class).active()).isTrue();
            assertThat(context).hasSingleBean(PrometheusScrapeController.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void 总开关关闭_埋点降级noOp_端点与Filter都不注册() {
        runner().withPropertyValues("pivotos.metrics.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(BusinessMetrics.class);
            assertThat(context.getBean(BusinessMetrics.class).active())
                    .as("关掉总开关后埋点必须降级为 no-op，而不是直接没有 Bean（否则业务注入处启动失败）")
                    .isFalse();
            assertThat(context).doesNotHaveBean(PrometheusScrapeController.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void 只关端点_埋点仍活跃_端点不注册() {
        runner().withPropertyValues("pivotos.metrics.prometheus.enabled=false").run(context -> {
            assertThat(context.getBean(BusinessMetrics.class).active()).isTrue();
            assertThat(context).doesNotHaveBean(PrometheusScrapeController.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void 只关慢接口_端点仍在_Filter不注册() {
        runner().withPropertyValues("pivotos.metrics.slow-request.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(PrometheusScrapeController.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void 没有MeterRegistry时降级noOp但上下文照常起来() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsInfrastructureAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BusinessMetrics.class);
                    assertThat(context.getBean(BusinessMetrics.class).active()).isFalse();
                    assertThat(context).doesNotHaveBean(PrometheusScrapeController.class);
                });
    }
}
