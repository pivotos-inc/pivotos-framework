package com.pivotos.starter.cloud.api.feign;

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
 *
 * <p><b>V3-S3 真机暴露的模块归属缺陷（务必记牢）</b>：本类原先放在 {@code starter-cloud}
 * （{@code com.pivotos.starter.cloud.sc.feign}），而 <b>alibaba 形态压根不依赖 starter-cloud
 * ——两个通道是互斥的 Maven profile</b>。于是 L5 那个「把装配条件放宽成 {CLOUD, ALIBABA}」的
 * 修法<b>对 alibaba 完全无效</b>：类根本不在 classpath 上，条件再宽也无从判定。
 * 表层现象是「Feign 调用全都通」，身份头却一个都没发（真机 IT 里下游 403 才暴露）。
 *
 * <p>结论：<b>凡是 cloud / alibaba 两个通道共用的能力，必须放在能被两边同时看见的
 * {@code cloud-api} 里</b>；放实现模块里就会出现「改了条件但没改模块 = 没改」。
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
