package com.pivotos.starter.cloud.sc.circuitbreaker;

import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * cloud 通道熔断配置（{@code pivotos.cloud.sc.circuit-breaker.*}）。
 *
 * <p><b>默认全部关闭</b>（{@code enabled=false}）：不配置时 Feign 客户端不被包装任何一层，
 * 行为与未引入本模块完全一致 —— 这是「引入 ≠ 生效」的另一面：生效必须显式打开。
 *
 * <p>配置口径刻意统一在 {@code pivotos.*} 下而不是要求用户去写 {@code resilience4j.*}：
 * 通道相关配置只有一处，排障时不用在两套前缀之间对齐。
 */
@Data
public class ScCircuitBreakerProperties {

    /**
     * 熔断包装总开关。false（默认）= 不包装，Feign 客户端原样返回。
     */
    private boolean enabled = false;

    /**
     * 熔断实例粒度：{@code interface}（默认，一个 Facade 一个实例，样本累积快）/ {@code method}。
     */
    private String granularity = "interface";

    /**
     * 熔断打开时的降级口径：{@code none}（默认，抛异常）/ {@code local}（回落本地实现）。
     */
    private String fallbackMode = "none";

    /** 名称前缀（便于在日志/指标里一眼认出是通道熔断） */
    private String namePrefix = "pivotosCloud";

    /** 滑动窗口大小（COUNT_BASED） */
    private int slidingWindowSize = 100;

    /** 计算失败率前所需的最小调用数：低于它不熔断，避免冷启动误判 */
    private int minimumNumberOfCalls = 20;

    /** 失败率阈值（百分比），达到即打开熔断 */
    private int failureRateThreshold = 50;

    /** OPEN 状态停留时长，之后进入 HALF_OPEN */
    private Duration waitDurationInOpenState = Duration.ofSeconds(10);

    /** HALF_OPEN 允许的探测调用数 */
    private int permittedNumberOfCallsInHalfOpenState = 5;

    /** 是否自动从 OPEN 转 HALF_OPEN（false = 靠下一次调用触发探测，更省一次无谓等待） */
    private boolean automaticTransitionFromOpenToHalfOpenEnabled = false;

    /**
     * 计入失败的异常类名（全限定名）。留空 = 记录所有 Throwable（Resilience4j 默认）。
     */
    private List<String> recordExceptions = new ArrayList<>();

    /**
     * 不计入失败的异常类名（全限定名）。典型用法：把业务异常排除掉，
     * 让熔断只反映「通道不可用」而不是「业务拒绝了请求」。
     */
    private List<String> ignoreExceptions = new ArrayList<>();

    /** 宽松解析降级口径，未知值回落 {@link FallbackMode#NONE}（配错也不静默改变行为） */
    public FallbackMode fallbackMode() {
        if (fallbackMode == null) {
            return FallbackMode.NONE;
        }
        for (FallbackMode mode : FallbackMode.values()) {
            if (mode.name().equalsIgnoreCase(fallbackMode.trim())) {
                return mode;
            }
        }
        return FallbackMode.NONE;
    }

    /** 是否按方法粒度建熔断实例（未知值回落 interface） */
    public boolean perMethod() {
        return "method".equalsIgnoreCase(granularity == null ? "" : granularity.trim());
    }
}
