package com.pivotos.starter.cloud.sc.config;

import com.pivotos.starter.cloud.api.CloudProvider;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.config.condition.ConditionalOnCloudProvider;
import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import com.pivotos.starter.cloud.sc.discovery.ScServiceInstanceProvider;
import com.pivotos.starter.cloud.sc.feign.CloudContextFeignInterceptor;
import com.pivotos.starter.cloud.sc.feign.FeignFacadeRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;

/**
 * Spring Cloud 原生通道自动配置（OpenFeign + LoadBalancer + CircuitBreaker）。
 *
 * <p>仅当 {@code pivotos.cloud.provider=cloud} 且 classpath 有 Feign 时装配；
 * {@code proxied} 为空则不替换任何 Bean（引入 ≠ 生效）。
 */
@AutoConfiguration
@EnableConfigurationProperties(ScCloudProperties.class)
@ConditionalOnCloudProvider(CloudProvider.CLOUD)
@ConditionalOnClass(name = "feign.Feign")
public class ScCloudAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ScCloudAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ServiceInstanceProvider.class)
    public ScServiceInstanceProvider scServiceInstanceProvider(ObjectProvider<DiscoveryClient> discoveryClient) {
        DiscoveryClient client = discoveryClient.getIfAvailable();
        log.info("[CLOUD][cloud] 服务实例提供者已装配：DiscoveryClient {}",
            client == null ? "缺席（未引入注册中心，调用将直连或不可用）" : "就绪");
        return new ScServiceInstanceProvider(client);
    }

    /**
     * Feign 出站上下文传播拦截器：注册为全局 {@code RequestInterceptor}，
     * 之后任何 Feign 调用都自动带上租户/身份/链路头，业务侧无感。
     */
    @Bean
    public CloudContextFeignInterceptor cloudContextFeignInterceptor(CloudProperties properties) {
        return new CloudContextFeignInterceptor(properties);
    }

    @Bean
    public static FeignFacadeRegistrar feignFacadeRegistrar(ScCloudProperties properties) {
        return new FeignFacadeRegistrar(properties);
    }
}
