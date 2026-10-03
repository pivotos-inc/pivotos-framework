package com.pivotos.starter.cloud.sc.discovery;

import com.pivotos.starter.cloud.api.discovery.ServiceInstance;
import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Spring Cloud 通道的服务实例提供者：适配 SC 的 {@link DiscoveryClient}
 * （底层可以是 Nacos / Eureka / K8s / 静态，与本通道解耦）。
 *
 * <p>{@code DiscoveryClient} 缺席（未引入注册中心 starter 或 discovery 被关）时
 * 一律返回空列表，{@link #isAvailable()} = false；<b>不抛异常</b>——
 * 注册中心不可达是部署常态，不该被放大成业务不可用。
 */
public class ScServiceInstanceProvider implements ServiceInstanceProvider {

    private final DiscoveryClient discoveryClient;

    public ScServiceInstanceProvider(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @Override
    public String id() {
        return "cloud";
    }

    @Override
    public List<ServiceInstance> list(String serviceId) {
        List<ServiceInstance> result = new ArrayList<>();
        if (discoveryClient == null || serviceId == null || serviceId.isBlank()) {
            return result;
        }
        for (org.springframework.cloud.client.ServiceInstance instance : discoveryClient.getInstances(serviceId)) {
            result.add(new ServiceInstance(
                serviceId,
                instance.getHost(),
                instance.getPort(),
                instance.isSecure(),
                new LinkedHashMap<>(instance.getMetadata())));
        }
        return result;
    }

    @Override
    public boolean isAvailable() {
        return discoveryClient != null;
    }
}
