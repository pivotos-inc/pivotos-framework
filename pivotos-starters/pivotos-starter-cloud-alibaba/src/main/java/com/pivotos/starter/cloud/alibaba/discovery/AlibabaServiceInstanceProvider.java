package com.pivotos.starter.cloud.alibaba.discovery;

import com.pivotos.starter.cloud.api.discovery.ServiceInstance;
import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Alibaba 通道的服务实例提供者：实例来自 Nacos（SCA 的
 * {@code NacosDiscoveryClient} 实现了 SC 的 {@link DiscoveryClient}）。
 *
 * <p>为什么复用 SC 抽象而不是直接调 {@code NacosServiceManager}：
 * 直接调会把本模块钉死在 Nacos 具体类上，一旦换注册中心（或 Nacos SDK 大版本改包）
 * 就要改代码；走 DiscoveryClient 则由 SCA 自己适配，本模块只认标准接口。
 *
 * <p>降级口径：DiscoveryClient 缺席 / Nacos 不可达 → 空列表 + {@code isAvailable=false}，
 * 不抛异常（中间件抖动不该放大成业务不可用）。
 */
public class AlibabaServiceInstanceProvider implements ServiceInstanceProvider {

    private final DiscoveryClient discoveryClient;

    public AlibabaServiceInstanceProvider(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @Override
    public String id() {
        return "alibaba";
    }

    @Override
    public List<ServiceInstance> list(String serviceId) {
        List<ServiceInstance> result = new ArrayList<>();
        if (discoveryClient == null || serviceId == null || serviceId.isBlank()) {
            return result;
        }
        try {
            for (org.springframework.cloud.client.ServiceInstance instance : discoveryClient.getInstances(serviceId)) {
                result.add(new ServiceInstance(
                    serviceId,
                    instance.getHost(),
                    instance.getPort(),
                    instance.isSecure(),
                    new LinkedHashMap<>(instance.getMetadata())));
            }
        } catch (Exception e) {
            // Nacos 连接失败等：返回空列表由调用方降级，不向上抛
            return result;
        }
        return result;
    }

    @Override
    public boolean isAvailable() {
        return discoveryClient != null;
    }
}
