package com.pivotos.starter.cloud.sc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Spring Cloud 原生通道配置（{@code pivotos.cloud.sc.*}）。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.cloud.sc")
public class ScCloudProperties {

    /**
     * 要被改成 Feign 远程调用的 Facade 接口全限定名（逗号分隔）。
     * 默认空 = 不替换任何 Bean（引入本模块但不配它，行为与不引一致）。
     */
    private String proxied = "";

    /** Feign 客户端的服务名（走 LoadBalancer 时用；配了 url 则直连） */
    private String serviceId = "pivotos-admin-server";

    /** 直连地址（形如 {@code http://127.0.0.1:8080}）；为空则按 serviceId 走负载均衡 */
    private String url = "";

    public Set<String> proxiedInterfaces() {
        Set<String> result = new LinkedHashSet<>();
        if (!StringUtils.hasText(proxied)) {
            return result;
        }
        for (String part : proxied.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
