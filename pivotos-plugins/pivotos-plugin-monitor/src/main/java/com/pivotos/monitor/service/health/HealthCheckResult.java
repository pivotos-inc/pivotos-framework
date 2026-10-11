package com.pivotos.monitor.service.health;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单项依赖探活结果。
 *
 * <h3>字段纪律（沿用 S127-B「缺三看不出回落」）</h3>
 * 每一项都必须能同时回答三个问题：<b>生效的是谁</b>（{@code implementation}）、
 * <b>配的是谁</b>（{@code configuredType}）、<b>是不是回落</b>（{@code fallback}）。
 * 只给 {@code up=false} 而不给这三项，运维看不出「配了 ES 却跑在 simple 上」这类事故。
 *
 * <h3>安全口径</h3>
 * {@code reason} / {@code error} 只进「明细」，而明细默认<b>只对已登录请求可见</b>
 * （{@code pivotos.ops.health.show-details-anonymously=true} 才会对匿名开放，生产严禁开）。
 * 且刻意不落任何一种 <b>URL / 主机 / 端口 / 账号</b>——这些有可能含凭据或内网拓扑。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthCheckResult {

    /** 检查项名（db / redis / search），即 {@code checks} 里的 key */
    private String name;

    /** 是否为关键依赖：关键项不可用 → 整体 DOWN（HTTP 503） */
    private boolean critical;

    /** 该项是否可用 */
    private boolean up;

    /** 生效实现（ClassName 或 simple/es-java 之类的实现码） */
    private String implementation;

    /** 配置值（pivotos.search.type 等），用于与 implementation 对照看是否回落 */
    private String configuredType;

    /** 是否已从配置的目标回落到兜底实现 */
    private boolean fallback;

    /** 不可用原因（明细可见） */
    private String reason;

    /** 异常类名（明细可见，不落 message，避免把含嫌疑串的报文对外） */
    private String error;

    /** 本次探活耗时（毫秒） */
    private long durationMs;

    /** 依赖未在运行时装配（如没引 Redis）——该项不参与 ready 结论 */
    private boolean absent;

    // ==================== 构造捷径 ====================

    public static HealthCheckResult absent(String name, boolean critical, String reason) {
        return HealthCheckResult.builder()
                .name(name).critical(critical).up(true).absent(true)
                .implementation("none").configuredType("none").reason(reason)
                .build();
    }

    public static HealthCheckResult error(String name, boolean critical, String reason, Exception e, long durationMs) {
        return HealthCheckResult.builder()
                .name(name).critical(critical).up(false)
                .reason(reason)
                .error(e == null ? null : e.getClass().getSimpleName())
                .durationMs(durationMs)
                .build();
    }
}
