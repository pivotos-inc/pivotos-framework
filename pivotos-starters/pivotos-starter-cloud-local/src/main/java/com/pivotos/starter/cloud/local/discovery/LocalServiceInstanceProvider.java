package com.pivotos.starter.cloud.local.discovery;

import com.pivotos.starter.cloud.api.discovery.ServiceInstance;
import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import com.pivotos.starter.cloud.local.config.LocalCloudProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * local 通道的服务实例提供者：地址只来自配置的静态实例表。
 *
 * <p><b>为什么不直连 Nacos Open API v1</b>（V3-S2 L2 清偿项）：
 * Nacos 3.1.1 的 v1 实例接口虽然仍在（{@code /nacos/v1/ns/instance} 路径未变），
 * 但类上已标 {@code @Deprecated}；3.2.0 起 legacy {@code /v1} {@code /v2} 被抽到
 * {@code api-legacy-adapter} 插件、需手动安装才能用。
 * 一条「升级注册中心就会断」的依赖不该留在**零依赖兜底通道**里——
 * Nacos 归 alibaba 通道走官方 SDK，local 通道只认静态表。
 */
public class LocalServiceInstanceProvider implements ServiceInstanceProvider {

    private final LocalCloudProperties properties;

    public LocalServiceInstanceProvider(LocalCloudProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public List<ServiceInstance> list(String serviceId) {
        Map<String, String[]> resolved = properties.resolvedInstances();
        String[] address = resolved.get(serviceId);
        List<ServiceInstance> result = new ArrayList<>();
        if (address == null) {
            return result;
        }
        result.add(ServiceInstance.of(serviceId, address[0], Integer.parseInt(address[1])));
        return result;
    }

    @Override
    public boolean isAvailable() {
        return !properties.resolvedInstances().isEmpty();
    }
}
