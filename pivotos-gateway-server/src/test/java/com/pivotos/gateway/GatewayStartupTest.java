package com.pivotos.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.filter.FilterSupplier;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关启动测试：证明 Gateway（WebMVC 变体）能真的起来，且路由与传播过滤器已装配。
 *
 * <p>用随机端口，避免与 8080（admin-server）/ 8101（xxl-job）冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayStartupTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GatewayMvcProperties gatewayProperties;

    @Test
    void context_loads() {
        assertThat(context).isNotNull();
    }

    @Test
    void route_to_admin_server_is_configured() {
        assertThat(gatewayProperties.getRoutes())
            .as("必须至少有一条到 admin-server 的路由，否则网关形同虚设")
            .isNotEmpty();
        assertThat(gatewayProperties.getRoutes().get(0).getUri().toString()).contains("8080");
    }

    @Test
    void cloud_context_filter_supplier_registered() {
        assertThat(context.getBeansOfType(FilterSupplier.class))
            .as("上下文传播过滤器必须注册进 Gateway 过滤器命名空间")
            .isNotEmpty();
    }
}
