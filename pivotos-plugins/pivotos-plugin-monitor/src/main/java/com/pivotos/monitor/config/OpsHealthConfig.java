package com.pivotos.monitor.config;

import com.pivotos.monitor.service.OpsHealthService;
import com.pivotos.monitor.service.health.DbHealthCheckStep;
import com.pivotos.monitor.service.health.HealthCheckStep;
import com.pivotos.monitor.service.health.RedisHealthCheckStep;
import com.pivotos.monitor.service.health.SearchHealthCheckStep;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.spi.SearchHealthProvider;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 运维探活装配。
 *
 * <h3>为什么依赖都走 {@code ObjectProvider}</h3>
 * 三个检查项都不敢直接注入 Bean：JUnit 单测、裁剪形态（没引 Redisson / 没配 DataSource）下
 * 直接注入会「启动即失败」，而探针恰恰是最不能把应用拖垮的东西。
 * 缺席时该 {@link HealthCheckStep#absent()} 返回 true，<b>不参与 ready 结论</b>。
 *
 * <h3>为什么整块挂 {@code @ConditionalOnProperty}</h3>
 * {@code pivotos.ops.health.enabled=false} 时这组 Bean 全都不注册 → 端点不存在 →
 * 请求落到全局异常处理返回既有形态（HTTP 200 + code=1004）。这是本框架「关闭 = 路径不存在」的一贯口径。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@Configuration
@EnableConfigurationProperties(OpsHealthProperties.class)
@ConditionalOnProperty(prefix = "pivotos.ops.health", name = "enabled", matchIfMissing = true)
public class OpsHealthConfig {

    @Bean
    @ConditionalOnMissingBean
    public DbHealthCheckStep dbHealthCheckStep(ObjectProvider<DataSource> dataSourceProvider) {
        return new DbHealthCheckStep(dataSourceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisHealthCheckStep redisHealthCheckStep(ObjectProvider<RedissonClient> redissonProvider) {
        return new RedisHealthCheckStep(redissonProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public SearchHealthCheckStep searchHealthCheckStep(ObjectProvider<SearchHealthProvider> providerRegistry,
                                                       ObjectProvider<SearchProperties> propertiesProvider) {
        return new SearchHealthCheckStep(providerRegistry, propertiesProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpsHealthService opsHealthService(OpsHealthProperties properties,
                                             ObjectProvider<HealthCheckStep> steps) {
        return new OpsHealthService(properties, steps.orderedStream().toList());
    }
}
