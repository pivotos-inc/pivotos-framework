package com.pivotos.starter.cloud.sc.config;

import com.pivotos.starter.cloud.api.CloudProvider;
import com.pivotos.starter.cloud.api.config.condition.ConditionalOnCloudProvider;
import com.pivotos.starter.cloud.sc.circuitbreaker.CloudCircuitBreakerStats;
import com.pivotos.starter.cloud.sc.circuitbreaker.CloudCircuitBreakerSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * cloud 通道熔断装配：只有 {@code provider=cloud} 且
 * {@code pivotos.cloud.sc.circuit-breaker.enabled=true} 时才存在。
 *
 * <p><b>为什么整类都挂在 {@code enabled=true} 上</b>：让「开关关闭」等价于「这些 Bean 根本不存在」，
 * 而不是「Bean 在但内部判断跳过」。前者能被测试直接断言（容器里查不到 Bean），
 * 后者只能靠读代码相信——这是「引入 ≠ 生效」这条铁律的另一半：不生效也要能被证明。
 */
@AutoConfiguration
@EnableConfigurationProperties(ScCloudProperties.class)
@ConditionalOnCloudProvider(CloudProvider.CLOUD)
@ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CircuitBreaker")
@ConditionalOnProperty(prefix = "pivotos.cloud.sc.circuit-breaker", name = "enabled", havingValue = "true")
public class ScCloudCircuitBreakerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ScCloudCircuitBreakerAutoConfiguration.class);

    /**
     * 熔断注册表。容器里已有（如 Resilience4j 自身自动配置提供）就复用，否则自建一个默认实例——
     * 实例级配置在 {@code registry.circuitBreaker(name, config)} 时传入，所以复用别人的 registry
     * 也不会让 {@code pivotos.cloud.sc.circuit-breaker.*} 失效。
     */
    @Bean
    @ConditionalOnMissingBean(CircuitBreakerRegistry.class)
    public CircuitBreakerRegistry cloudCircuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    @ConditionalOnMissingBean(CloudCircuitBreakerStats.class)
    public CloudCircuitBreakerStats cloudCircuitBreakerStats() {
        return new CloudCircuitBreakerStats();
    }

    @Bean
    @ConditionalOnMissingBean(CloudCircuitBreakerSupport.class)
    public CloudCircuitBreakerSupport cloudCircuitBreakerSupport(ScCloudProperties properties,
                                                                 CircuitBreakerRegistry registry,
                                                                 CloudCircuitBreakerStats stats) {
        CloudCircuitBreakerSupport support =
            new CloudCircuitBreakerSupport(properties.getCircuitBreaker(), registry, stats);
        log.info("[CLOUD][cloud] 熔断包装已启用：{}", support.describe());
        return support;
    }
}
