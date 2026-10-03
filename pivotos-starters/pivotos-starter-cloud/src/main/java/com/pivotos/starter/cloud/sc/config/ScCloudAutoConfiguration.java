package com.pivotos.starter.cloud.sc.config;

import com.pivotos.starter.cloud.api.CloudProvider;
import com.pivotos.starter.cloud.api.config.condition.ConditionalOnCloudProvider;
import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import com.pivotos.starter.cloud.sc.discovery.ScServiceInstanceProvider;
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
     * 注：Feign 出站上下文传播拦截器不在本类装配 —— 它要同时覆盖 cloud 与 alibaba 两个形态，
     * 已拆到 {@link com.pivotos.starter.cloud.sc.feign.CloudContextFeignAutoConfiguration}。
     * 留在本类的后果：alibaba 形态（provider=alibaba）整份拦截器缺席，Feign 出站不带身份头。
     */

    /**
     * Facade 远程替换注册器。<b>必须是 static @Bean</b>：BFPP 需在容器刷新早期实例化。
     *
     * <p>刻意<b>不接收 {@link ScCloudProperties} 参数</b>：BFPP 的构造参数会被提前解析，
     * 会把 properties Bean 在绑定后处理器之前创建出来，导致配置静默失效（详见 {@code PropertyBinder}）。
     * 注册器自己从 Environment 绑定。
     */
    @Bean
    public static FeignFacadeRegistrar feignFacadeRegistrar() {
        return new FeignFacadeRegistrar();
    }
}
