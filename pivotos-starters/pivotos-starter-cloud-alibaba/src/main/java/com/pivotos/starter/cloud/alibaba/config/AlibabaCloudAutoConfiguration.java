package com.pivotos.starter.cloud.alibaba.config;

import com.pivotos.starter.cloud.alibaba.discovery.AlibabaServiceInstanceProvider;
import com.pivotos.starter.cloud.alibaba.sentinel.SentinelGuard;
import com.pivotos.starter.cloud.api.CloudProvider;
import com.pivotos.starter.cloud.api.config.condition.ConditionalOnCloudProvider;
import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Spring Cloud Alibaba 通道自动配置（Nacos 注册发现 + Sentinel）。
 *
 * <p>仅当 {@code pivotos.cloud.provider=alibaba} 时装配。
 * Nacos 的注册动作由 SCA starter 自己完成，本类只做三件事：
 * 实例查询适配、Sentinel 规则加载、启动信息对账（配置地址 vs 实际可用）。
 */
@AutoConfiguration
@EnableConfigurationProperties(AlibabaCloudProperties.class)
@ConditionalOnCloudProvider(CloudProvider.ALIBABA)
@ConditionalOnClass(name = "com.alibaba.cloud.nacos.NacosDiscoveryProperties")
public class AlibabaCloudAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AlibabaCloudAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ServiceInstanceProvider.class)
    public AlibabaServiceInstanceProvider alibabaServiceInstanceProvider(ObjectProvider<DiscoveryClient> discoveryClient,
                                                                         Environment environment) {
        DiscoveryClient client = discoveryClient.getIfAvailable();
        String serverAddr = environment.getProperty("spring.cloud.nacos.discovery.server-addr", "(未配置)");
        log.info("[CLOUD][alibaba] 服务实例提供者已装配：DiscoveryClient {}，nacos server-addr={}",
            client == null ? "缺席" : "就绪", serverAddr);
        return new AlibabaServiceInstanceProvider(client);
    }

    /**
     * 配置中心落地状态自检（V3-S2 L6）。
     *
     * <p>刻意只挂在 {@code NacosConfigDataLocationResolver} 上而不是本类的类级条件：
     * 配置中心是 Boot 层的 {@code spring.config.import} 能力，与 {@code pivotos.cloud.provider}
     * 选哪个通道无关；只要 jar 里带了 nacos-config，就该把「有没有真的读到配置」打出来。
     */
    @Bean
    @ConditionalOnMissingBean(NacosConfigStateLogger.class)
    @ConditionalOnClass(name = "com.alibaba.cloud.nacos.configdata.NacosConfigDataLocationResolver")
    public NacosConfigStateLogger nacosConfigStateLogger(ConfigurableEnvironment environment) {
        return new NacosConfigStateLogger(environment);
    }

    @Bean
    @ConditionalOnProperty(prefix = "pivotos.cloud.alibaba", name = "sentinel-enabled", havingValue = "true")
    public SentinelGuard sentinelGuard(AlibabaCloudProperties properties) {
        log.info("[CLOUD][alibaba] Sentinel 保护已启用，规则 {} 条", properties.getRules() == null ? 0 : properties.getRules().size());
        return new SentinelGuard(properties);
    }
}
