package com.pivotos.starter.cloud.sc.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * 熔断打开时抛出的异常。
 *
 * <p>刻意做成<b>独立异常类型</b>而不是把原始异常原样抛出：调用方需要能区分
 * 「我这次调用真的失败了」和「通道已经熔断，请求根本没发出去」——后者的处置方式完全不同
 * （前者重试可能有用，后者重试只会继续被拒）。
 */
public class CloudCircuitBreakerOpenException extends RuntimeException {

    private final String circuitBreakerName;
    private final String state;
    private final String target;

    public CloudCircuitBreakerOpenException(String circuitBreakerName,
                                            CircuitBreaker.State state,
                                            String target,
                                            float failureRate) {
        super("[CLOUD][cloud][CB] 熔断已打开，调用未发出：name=" + circuitBreakerName
            + "，state=" + (state == null ? "UNKNOWN" : state.name())
            + "，target=" + target
            + "，failureRate=" + failureRate + "%");
        this.circuitBreakerName = circuitBreakerName;
        this.state = state == null ? "UNKNOWN" : state.name();
        this.target = target;
    }

    public String getCircuitBreakerName() {
        return circuitBreakerName;
    }

    public String getState() {
        return state;
    }

    public String getTarget() {
        return target;
    }
}
