package com.pivotos.starter.cloud.api.discovery;

import java.util.List;

/**
 * 服务实例提供者 SPI：三通道各自实现（local 静态表 / cloud LoadBalancer / alibaba Nacos）。
 *
 * <p>契约两条：
 * <ol>
 *   <li><b>不可用返回空列表，不抛异常</b>——注册中心不可达是部署常态，
 *       抛异常会把「中间件抖动」放大成「业务不可用」；由调用方决定降级还是报错。</li>
 *   <li>{@link #id()} 必须与 {@code pivotos.cloud.provider} 取值一致，
 *       便于启动日志与实际生效通道对账（S127-B 的「生效/配置/回落三件套」同款口径）。</li>
 * </ol>
 */
public interface ServiceInstanceProvider {

    /** 通道标识：local / cloud / alibaba */
    String id();

    /** 列出健康实例；不可用或无实例返回空列表 */
    List<ServiceInstance> list(String serviceId);

    /** 取第一个实例（当前无负载均衡策略，取首个即可；将来换 LB 时此处是唯一改动点） */
    default ServiceInstance first(String serviceId) {
        List<ServiceInstance> instances = list(serviceId);
        return instances == null || instances.isEmpty() ? null : instances.get(0);
    }

    /** 该通道当前是否可用（注册中心未启用/未就绪一律 false） */
    default boolean isAvailable() {
        return true;
    }
}
