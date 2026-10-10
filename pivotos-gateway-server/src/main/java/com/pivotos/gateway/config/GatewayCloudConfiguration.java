package com.pivotos.gateway.config;

import com.pivotos.gateway.filter.CloudContextGatewayFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.server.mvc.filter.FilterSupplier;
import org.springframework.cloud.gateway.server.mvc.filter.SimpleFilterSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关装配：把上下文传播过滤器注册进 Gateway 的过滤器命名空间。
 */
@Configuration
public class GatewayCloudConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayCloudConfiguration.class);

    @Bean
    public FilterSupplier cloudContextFilterSupplier() {
        log.info("[CLOUD][gateway] 上下文传播过滤器已注册：{}", CloudContextGatewayFilters.class.getSimpleName());
        return new SimpleFilterSupplier(CloudContextGatewayFilters.class);
    }
}
