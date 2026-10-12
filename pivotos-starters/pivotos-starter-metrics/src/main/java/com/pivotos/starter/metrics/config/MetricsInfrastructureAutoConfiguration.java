package com.pivotos.starter.metrics.config;

import com.pivotos.starter.metrics.endpoint.PrometheusScrapeController;
import com.pivotos.starter.metrics.jdbc.DruidPoolMetricsBinder;
import com.pivotos.starter.metrics.support.BusinessMetrics;
import com.pivotos.starter.metrics.web.SlowRequestMetricsFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
// Boot 4 把 MeterRegistryCustomizer 从 io.micrometer.core.instrument 挪到了 Boot 自己的包下，
// 按 Boot 3 的老路径 import 会直接编译失败（坑，勿重踩）。
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * 指标基础设施自动配置（V3.1-F · P1-B8-1）。
 *
 * <h3>本模块带来的指标（全部由 Boot 自动配置提供，本类只负责「打开」它们）</h3>
 * <pre>
 *   jvm.*             JVM 内存 / GC / 线程 / 类加载（spring-boot-micrometer-metrics）
 *   system.* / process.*   CPU / 文件描述符 / 进程（同上）
 *   logback.*         日志事件计数（同上）
 *   hikaricp.*        连接池（spring-boot-jdbc，早已在 classpath 上，缺的只是一个 MeterRegistry）
 *   http.server.requests  HTTP 请求直方图（spring-boot-webmvc + micrometer-observation）
 *   lettuce.*         Redis 命令观测（spring-boot-data-redis）
 * </pre>
 * 自研补充三类业务指标（见 {@link BusinessMetrics}）：检索调用量 / AI 调用量 / 慢接口。
 *
 * <h3>为什么显式 after 两个 Boot 自动配置</h3>
 * {@code PrometheusScrapeController} 依赖 {@code PrometheusMeterRegistry}，而该 registry 由
 * {@code PrometheusMetricsExportAutoConfiguration} 产出；自动配置之间的顺序不声明就不保证，
 * {@code @ConditionalOnBean} 会静默不成立（端点不注册、且不报错），
 * 这是「配了但没生效」最容易踩的形态。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · P1-B8-1）
 */
@AutoConfiguration(
        after = {
                org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration.class,
                org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration.class
        })
@ConditionalOnProperty(prefix = "pivotos.metrics", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsInfrastructureAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MetricsInfrastructureAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public BusinessMetrics businessMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("[METRICS] classpath 上没有 MeterRegistry，业务埋点降级为 no-op（指标不会出现在任何地方）");
        }
        return new BusinessMetrics(registry);
    }

    /** 统一打 application 标签：多实例/多应用共用一个 Prometheus 时靠它区分 */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> pivotOsMetricsCommonTags(Environment environment) {
        String application = environment.getProperty("spring.application.name", "pivotos");
        return registry -> registry.config().commonTags("application", application);
    }

    /**
     * Prometheus 抓取端点。免鉴权（Prometheus 抓取不带登录态），
     * 已登记为匿名端点白名单；生产必须在网络层限制内网。
     */
    @Bean
    @ConditionalOnProperty(prefix = "pivotos.metrics.prometheus", name = "enabled", matchIfMissing = true)
    @ConditionalOnBean(PrometheusMeterRegistry.class)
    public PrometheusScrapeController prometheusScrapeController(PrometheusMeterRegistry registry) {
        log.info("[METRICS] Prometheus 抓取端点已注册（免鉴权，生产须限内网）");
        return new PrometheusScrapeController(registry);
    }

    /**
     * Druid 连接池指标。
     *
     * <p>做成嵌套配置类 + {@code @ConditionalOnClass(name=...)} 而不是直接 {@code @Bean}：
     * druid 是 optional 依赖，直接写在主自动配置里会让「没用 Druid 的应用」在解析 bean 方法签名时
     * 触发 {@code NoClassDefFoundError}。条件用字符串形式（name=）而非类字面量，
     * 正是为了让 Spring 用 ASM 读元数据、不真正加载 Druid 类。
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.alibaba.druid.pool.DruidDataSource")
    static class DruidPoolMetricsConfiguration {

        @Bean
        public DruidPoolMetricsBinder druidPoolMetricsBinder(ObjectProvider<javax.sql.DataSource> dataSourceProvider) {
            return new DruidPoolMetricsBinder(dataSourceProvider.getIfAvailable());
        }
    }

    /** 慢接口统计 Filter */
    @Bean
    @ConditionalOnProperty(prefix = "pivotos.metrics.slow-request", name = "enabled", matchIfMissing = true)
    public FilterRegistrationBean<SlowRequestMetricsFilter> slowRequestMetricsFilter(
            BusinessMetrics businessMetrics, MetricsProperties properties) {
        SlowRequestMetricsFilter filter =
                new SlowRequestMetricsFilter(businessMetrics, properties.getSlowRequest().getThresholdMs());
        log.info("[METRICS] 慢请求 Filter 已注册：thresholdMs={}", properties.getSlowRequest().getThresholdMs());
        FilterRegistrationBean<SlowRequestMetricsFilter> registration = new FilterRegistrationBean<>(filter);
        // 放在最后：贴近 DispatcherServlet，量到的时间才接近真实业务耗时
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
