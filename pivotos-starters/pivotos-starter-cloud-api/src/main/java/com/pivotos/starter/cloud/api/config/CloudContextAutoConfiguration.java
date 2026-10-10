package com.pivotos.starter.cloud.api.config;

import com.pivotos.starter.cloud.api.context.CloudContextFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 上下文传播自动配置（SPI 公共层，三通道共用）。
 *
 * <p>这里只装配「入站恢复过滤器」这一件与通道无关的东西；
 * 出站传播是被动调用的（各通道拦截器调 {@code CloudContextCodec.capture()}），不需要 Bean。
 *
 * <p>条件一律 {@code @ConditionalOnProperty}：S133 踩坑 11 的纪律——
 * 不用 {@code @ConditionalOnBean}，避免自动配置时序窗口。
 */
@AutoConfiguration
@EnableConfigurationProperties(CloudProperties.class)
@ConditionalOnProperty(prefix = "pivotos.cloud", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CloudContextAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudContextAutoConfiguration.class);

    /**
     * 入站恢复过滤器。顺序 = 最高优先级 + 10：在 TraceIdFilter（最高优先级）之后、
     * 租户过滤器（+30）之前，使「内部调用带来的租户」先于常规解析生效。
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "pivotos.cloud.context", name = "filter-enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<CloudContextFilter> cloudContextFilterRegistration(CloudProperties properties) {
        log.info("[CLOUD][context] 入站上下文恢复已装配：restoreTenant={}, restoreLogin={}, internalTokenConfigured={}",
            properties.getContext().isRestoreTenant(),
            properties.getContext().isRestoreLogin(),
            properties.getContext().getInternalToken() != null && !properties.getContext().getInternalToken().isBlank());

        FilterRegistrationBean<CloudContextFilter> registration =
            new FilterRegistrationBean<>(new CloudContextFilter(properties.getContext()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
