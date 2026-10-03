package com.pivotos.starter.cloud.local.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.cloud.api.CloudProvider;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.config.condition.ConditionalOnCloudProvider;
import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import com.pivotos.starter.cloud.local.discovery.LocalServiceInstanceProvider;
import com.pivotos.starter.cloud.local.rpc.FacadeRpcConstants;
import com.pivotos.starter.cloud.local.rpc.FacadeRpcRegistrar;
import com.pivotos.starter.cloud.local.rpc.FacadeRpcServlet;
import com.pivotos.starter.cloud.local.rpc.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 自研 local 通道自动配置。
 *
 * <p>装配边界（沿用 S133 三条铁律）：
 * <ul>
 *   <li>仅当 {@code pivotos.cloud.provider=local}（缺省即 local）时装配；</li>
 *   <li>{@code proxied} 为空 → 不做任何 Bean 替换（进程内调用，零网络行为）；</li>
 *   <li>RPC 服务端点默认不注册，注册也强制校验内部调用凭证。</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(LocalCloudProperties.class)
@ConditionalOnCloudProvider(CloudProvider.LOCAL)
public class LocalCloudAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LocalCloudAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ServiceInstanceProvider.class)
    public LocalServiceInstanceProvider localServiceInstanceProvider(LocalCloudProperties properties) {
        log.info("[CLOUD][local] 服务实例提供者已装配：静态实例 {} 条（通道默认零行为，proxied 为空时不产生调用）",
            properties.resolvedInstances().size());
        return new LocalServiceInstanceProvider(properties);
    }

    /**
     * Facade 远程替换注册器。<b>必须是 static @Bean</b>：
     * {@code BeanDefinitionRegistryPostProcessor} 需要在容器刷新早期实例化，
     * 非 static 的 @Bean 方法会迫使宿主配置类过早初始化并触发 BFPP 警告。
     */
    @Bean
    public static FacadeRpcRegistrar facadeRpcRegistrar(LocalCloudProperties localProps, CloudProperties cloudProps) {
        return new FacadeRpcRegistrar(localProps, cloudProps);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper cloudLocalRpcObjectMapper() {
        return JsonSupport.fallback();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServlet")
    @ConditionalOnProperty(prefix = "pivotos.cloud.local", name = "server-enabled", havingValue = "true")
    public ServletRegistrationBean<FacadeRpcServlet> facadeRpcServlet(CloudProperties cloudProps) {
        boolean tokenConfigured = cloudProps.getContext().getInternalToken() != null
            && !cloudProps.getContext().getInternalToken().isBlank();
        if (!tokenConfigured) {
            // 配错要响：端点已开但没配凭证 = 公开反射后门，直接 fail-fast
            throw new IllegalStateException(
                "[CLOUD][local] FAIL-FAST：pivotos.cloud.local.server-enabled=true 必须同时配置 "
                    + "pivotos.cloud.context.internal-token，否则 /__rpc/invoke 将成为公开反射后门");
        }
        log.info("[CLOUD][local] RPC 服务端点已注册：{}（强制内部凭证校验）", FacadeRpcConstants.ENDPOINT);
        ServletRegistrationBean<FacadeRpcServlet> registration =
            new ServletRegistrationBean<>(new FacadeRpcServlet(cloudProps), FacadeRpcConstants.ENDPOINT);
        registration.setLoadOnStartup(1);
        return registration;
    }
}
