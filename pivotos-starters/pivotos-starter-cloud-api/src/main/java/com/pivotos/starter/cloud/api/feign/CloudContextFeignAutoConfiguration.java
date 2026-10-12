package com.pivotos.starter.cloud.api.feign;

import com.pivotos.starter.cloud.api.CloudProvider;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.config.condition.ConditionalOnCloudProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Feign 出站上下文传播拦截器的装配（cloud 与 alibaba 共用）。
 *
 * <p><b>为什么必须在 {@code cloud-api} 而不是 {@code starter-cloud}</b>（V3-S3 真机取证）：
 * cloud 与 alibaba 是<b>互斥的两个 Maven profile</b>，{@code -P alibaba} 打出来的包里
 * 没有 starter-cloud 的任何类。装配条件写 {@code {CLOUD, ALIBABA}} 只是「允许 alibaba」，
 * 前提是这份类本身得在 classpath 上——放在实现模块里等于 alibaba 永远装配不到。
 * 这个缺陷的隐蔽之处在于：Feign 调用 100% 成功，只是身份头一个都没发，
 * 只有「下游回显自己恢复成什么身份」的可观测点就位后（V3-S3 ④ 的 Facade HTTP 端点）
 * 才会以 403 的形式暴露。
 *
 * <p><b>为什么从 {@code ScCloudAutoConfiguration} 里拆出来</b>：出站身份传播是
 * 「凡 Feign 出站就该带」的能力，而把整个 {@code ScCloudAutoConfiguration} 放宽到 alibaba
 * 又会连带装配出 {@code ScServiceInstanceProvider}，与 Nacos 通道自己的实例提供者抢 Bean。
 *
 * <p>local 通道不走 Feign（自研 JSON-RPC 自己带头），因此不进条件。
 * 默认形态（无 spring-cloud）由 {@code @ConditionalOnClass(name = "feign.Feign")} 兜住。
 */
@AutoConfiguration
@EnableConfigurationProperties(CloudProperties.class)
@ConditionalOnCloudProvider({CloudProvider.CLOUD, CloudProvider.ALIBABA})
@ConditionalOnClass(name = "feign.Feign")
public class CloudContextFeignAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudContextFeignAutoConfiguration.class);

    /**
     * 全局 {@code RequestInterceptor}：之后任何 Feign 出站调用都自动带租户/身份/链路头。
     */
    @Bean
    public CloudContextFeignInterceptor cloudContextFeignInterceptor(CloudProperties properties) {
        log.info("[CLOUD][feign] 出站上下文传播已装配：propagate={}，internalTokenConfigured={}",
            properties.getContext().isPropagate(),
            properties.getContext().getInternalToken() != null && !properties.getContext().getInternalToken().isBlank());
        return new CloudContextFeignInterceptor(properties);
    }
}
