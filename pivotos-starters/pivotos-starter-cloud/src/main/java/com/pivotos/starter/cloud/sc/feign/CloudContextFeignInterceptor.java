package com.pivotos.starter.cloud.sc.feign;

import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.context.CloudContextCodec;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.Map;

/**
 * Feign 出站上下文传播拦截器。
 *
 * <p>这是「身份与租户上下文传播只做一次」的落点之一：这里只调
 * {@link CloudContextCodec#capture()} / {@code toHeaders}，不自己拼头名、不自己读上下文。
 * local 通道的 HTTP 客户端、Gateway 的转发过滤器走的是同一套方法。
 */
public class CloudContextFeignInterceptor implements RequestInterceptor {

    private final CloudProperties properties;

    public CloudContextFeignInterceptor(CloudProperties properties) {
        this.properties = properties;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (properties == null || !properties.getContext().isPropagate()) {
            return;
        }
        for (Map.Entry<String, String> header : CloudContextCodec.toHeaders(CloudContextCodec.capture()).entrySet()) {
            template.header(header.getKey(), header.getValue());
        }
        String internalToken = properties.getContext().getInternalToken();
        if (internalToken != null && !internalToken.isBlank()) {
            template.header(CloudHeaders.INTERNAL_TOKEN, internalToken);
        }
    }
}
