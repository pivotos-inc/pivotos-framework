package com.pivotos.starter.cloud.sc.feign;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 出站传播测试：证明 cloud 通道走的是 SPI 公共层那一套编解码器（不是自己拼头）。
 */
class CloudContextFeignInterceptorTest {

    @Test
    void injects_context_headers_when_bound() {
        CloudProperties properties = new CloudProperties();
        properties.getContext().setInternalToken("tok");
        CloudContextFeignInterceptor interceptor = new CloudContextFeignInterceptor(properties);
        RequestTemplate template = new RequestTemplate();

        ScopedValue.where(TenantContext.KEY, 12L).run(() -> {
            ScopedValue.where(LoginContext.KEY, new LoginUser(77L, "erin", "sys-user", 12L))
                .run(() -> interceptor.apply(template));
        });

        assertThat(template.headers())
            .containsKey(CloudHeaders.TENANT_ID)
            .containsKey(CloudHeaders.USER_ID)
            .containsKey(CloudHeaders.USERNAME);
        assertThat(template.headers().get(CloudHeaders.TENANT_ID)).containsExactly("12");
        assertThat(template.headers().get(CloudHeaders.INTERNAL_TOKEN)).containsExactly("tok");
    }

    @Test
    void does_nothing_when_propagate_disabled() {
        CloudProperties properties = new CloudProperties();
        properties.getContext().setPropagate(false);
        CloudContextFeignInterceptor interceptor = new CloudContextFeignInterceptor(properties);
        RequestTemplate template = new RequestTemplate();

        ScopedValue.where(TenantContext.KEY, 12L).run(() -> interceptor.apply(template));
        assertThat(template.headers()).as("传播开关关闭后不得写任何上下文头").isEmpty();
    }

    @Test
    void does_nothing_when_no_context_bound() {
        CloudContextFeignInterceptor interceptor = new CloudContextFeignInterceptor(new CloudProperties());
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers()).doesNotContainKey(CloudHeaders.TENANT_ID);
    }
}
