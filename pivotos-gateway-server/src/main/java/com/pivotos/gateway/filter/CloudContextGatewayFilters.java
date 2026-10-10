package com.pivotos.gateway.filter;

import com.pivotos.starter.cloud.api.context.CloudContextCodec;
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Map;
import java.util.function.Function;

/**
 * 网关侧上下文传播过滤器（Gateway Server WebMVC 的 {@code FilterSupplier} 扩展点）。
 *
 * <p>与 local 通道的 HTTP 客户端、cloud 通道的 Feign 拦截器<b>共用同一个编解码器</b>
 * （{@link CloudContextCodec}）：头名、字段、取值口径只有一处定义，不会漂移。
 *
 * <p>实际效果分两种：
 * <ul>
 *   <li>上游请求已带 {@code X-PivotOS-*} 头 → 网关按 HTTP 代理语义天然透传；</li>
 *   <li>网关自身持有上下文（例如将来在网关做鉴权）→ 由本过滤器补齐缺失的头。</li>
 * </ul>
 */
public final class CloudContextGatewayFilters {

    private CloudContextGatewayFilters() {
    }

    /** 过滤器工厂方法：由 {@code SimpleFilterSupplier} 反射收集并注册到网关过滤器命名空间 */
    public static Function<ServerRequest, ServerRequest> pivotosCloudContext() {
        return request -> {
            Map<String, String> headers = CloudContextCodec.toHeaders(CloudContextCodec.capture());
            ServerRequest current = request;
            for (Map.Entry<String, String> header : headers.entrySet()) {
                current = BeforeFilterFunctions.addRequestHeader(header.getKey(), header.getValue()).apply(current);
            }
            return current;
        };
    }
}
