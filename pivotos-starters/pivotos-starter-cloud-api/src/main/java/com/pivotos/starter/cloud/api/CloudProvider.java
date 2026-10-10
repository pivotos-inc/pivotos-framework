package com.pivotos.starter.cloud.api;

/**
 * 微服务通道枚举：{@code pivotos.cloud.provider} 的取值。
 *
 * <p><b>三通道职责不重叠（S133 拍板）</b>：
 * <ul>
 *   <li>{@link #LOCAL} —— 自研通道。零三方中间件依赖、fat jar 直起即可跨进程调用；
 *       不追平熔断/限流/分布式事务（那两个通道的事）。<b>单体形态默认通道</b>。</li>
 *   <li>{@link #CLOUD} —— Spring Cloud 原生：OpenFeign + LoadBalancer + CircuitBreaker。</li>
 *   <li>{@link #ALIBABA} —— Spring Cloud Alibaba：Nacos 注册发现 + Sentinel 流量治理。</li>
 * </ul>
 *
 * <p>身份与租户上下文传播<b>只在本契约包实现一次</b>（{@code context} 包），
 * 三个通道实现一律复用，禁止各自下沉——否则三份实现必然漂移。
 */
public enum CloudProvider {

    /** 自研通道（零三方依赖，单体形态默认） */
    LOCAL("local"),

    /** Spring Cloud 原生通道 */
    CLOUD("cloud"),

    /** Spring Cloud Alibaba 通道（Nacos / Sentinel） */
    ALIBABA("alibaba");

    private final String value;

    CloudProvider(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * 宽松解析：忽略大小写与空值，解析不了回落到 {@link #LOCAL}（单体形态兜底）。
     * 不抛异常——配错通道不该让应用起不来，由启动日志 WARN 指明实际生效通道。
     */
    public static CloudProvider of(String raw) {
        if (raw == null || raw.isBlank()) {
            return LOCAL;
        }
        String v = raw.trim().toLowerCase();
        for (CloudProvider p : values()) {
            if (p.value.equals(v) || p.name().equalsIgnoreCase(v)) {
                return p;
            }
        }
        return LOCAL;
    }
}
