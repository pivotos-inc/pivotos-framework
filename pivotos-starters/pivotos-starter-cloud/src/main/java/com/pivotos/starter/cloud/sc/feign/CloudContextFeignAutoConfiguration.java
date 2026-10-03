package com.pivotos.starter.cloud.sc.feign;

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
 * <p><b>为什么从 {@code ScCloudAutoConfiguration} 里拆出来</b>：
 * 出站身份传播是「凡 Feign 出站就该带」的能力，而 alibaba 形态（-P alibaba）下
 * provider=alibaba，原装配条件 {@code @ConditionalOnCloudProvider(CLOUD)} 不成立，
 * 拦截器整份缺席 —— 于是 Nacos 形态的 Feign 调用<b>一个身份头都不带</b>（L5 实测缺口）。
 * 把整个 {@code ScCloudAutoConfiguration} 放宽到 alibaba 又会连带装配出
 * {@code ScServiceInstanceProvider}，与 Nacos 通道自己的实例提供者抢 Bean，故单独拆一份。
 *
 * <p>local 通道不走 Feign（自研 JSON-RPC 自己带头），因此不进条件。
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
