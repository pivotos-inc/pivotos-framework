package com.pivotos.starter.cloud.local.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 自研 local 通道配置（{@code pivotos.cloud.local.*}）。
 *
 * <p><b>引入 ≠ 生效</b>（S133 三条铁律之一）：两个真正会产生网络行为的开关
 * （{@code proxied} 与 {@code server-enabled}）默认都是空 / false，
 * 因此单体形态引入本模块后行为与引入前<b>完全一致</b>。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.cloud.local")
public class LocalCloudProperties {

    /**
     * 要被改成远程调用的 Facade 接口全限定名（逗号分隔）。
     * <b>默认为空 = 不做任何代理替换</b>——本地注入仍然是本地 Bean。
     */
    private String proxied = "";

    /** 是否暴露 RPC 服务端点 {@code /__rpc/invoke}（默认关闭；开启必须配 internal-token） */
    private boolean serverEnabled = false;

    /**
     * 静态实例表：{@code serviceId -> host:port}。
     * local 通道不连任何注册中心（也不直连 Nacos Open API，见下），地址只来自这里。
     */
    private Map<String, String> instances = new LinkedHashMap<>();

    /** 调用超时（毫秒） */
    private long timeoutMs = 5000;

    /**
     * 目标服务地址（形如 {@code http://127.0.0.1:8080}）。
     * 配了它就不用查实例表——两台机器直连的最小形态。
     */
    private String baseUrl = "";

    /**
     * 未配 {@code base-url} 时，从静态实例表按此服务名解析目标。
     */
    private String serviceId = "pivotos-admin-server";

    /** 解析后的代理接口集合（去空白、去空串） */
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

    /**
     * 解析静态实例表（{@code host:port} → ServiceInstance）。
     * 解析不了的格式跳过并记录 WARN，不让一个配错的地址拖垮整个实例表。
     */
    public Map<String, String[]> resolvedInstances() {
        Map<String, String[]> resolved = new LinkedHashMap<>();
        if (instances == null) {
            return resolved;
        }
        instances.forEach((serviceId, address) -> {
            if (address == null || !address.contains(":")) {
                return;
            }
            String[] hp = address.split(":", 2);
            try {
                resolved.put(serviceId, new String[]{hp[0].trim(), String.valueOf(Integer.parseInt(hp[1].trim()))});
            } catch (NumberFormatException ignored) {
                // 端口非法：跳过该条，其余照常
            }
        });
        return resolved;
    }
}
