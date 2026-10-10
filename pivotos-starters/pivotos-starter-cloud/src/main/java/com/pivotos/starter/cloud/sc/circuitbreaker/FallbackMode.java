package com.pivotos.starter.cloud.sc.circuitbreaker;

/**
 * 熔断打开（OPEN）时的降级口径。
 *
 * <p><b>为什么没有 {@code DEFAULT_VALUE}（返回 null / 空集合）</b>：
 * 返回一个「看起来合法」的空值，会把「下游不可用」伪装成「业务本来就没数据」——
 * 调用方拿不到任何信号，故障只会在更远的地方以更难查的形态爆出来。
 * 本项目的口径是<b>降级必须显式</b>：要么抛一个带状态的异常，要么落到真实可执行的替代实现。
 */
public enum FallbackMode {

    /** 不降级：抛 {@link CloudCircuitBreakerOpenException}（默认）。绝不静默吞异常。 */
    NONE,

    /**
     * 回落到容器内的本地实现（替换前保留的 {@code <beanName>$Local}）。
     * 语义 = 退化成单体行为；本地实现缺席时照抛异常，绝不返回 null。
     */
    LOCAL
}
