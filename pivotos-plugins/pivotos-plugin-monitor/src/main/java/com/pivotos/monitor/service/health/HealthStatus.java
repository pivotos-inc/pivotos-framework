package com.pivotos.monitor.service.health;

/**
 * 探活结论三态。
 *
 * <h3>为什么要三态而不是 UP/DOWN</h3>
 * 本框架大量依赖都是「可降级」的（检索配了 ES 但拿不到实现就回落 simple），把这类依赖判 DOWN
 * 会让 Nginx / k8s 把还能正常服务的实例整体摘掉。<b>只有关键依赖不可用才 DOWN</b>，其余情况给
 * {@link #DEGRADED}——HTTP 仍 200，但 {@code checks} 里能看出哪一项掉了，运维据此决定是否介入。
 *
 * <h3>与 HTTP 状态码的映射</h3>
 * {@link #UP} / {@link #DEGRADED} → 200，{@link #DOWN} → 503。
 * 注意：<b>全局异常处理仍把「路径不存在」转成 HTTP 200 + code=1004</b>（既有语义，本轮不动），
 * 所以这两个探针端点是全系统里少数「状态码即结论」的地方，探活请盯状态码，其余端点仍要读业务码。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
public enum HealthStatus {

    /** 全部检查项可用 */
    UP,

    /** 关键依赖均可用，但有非关键依赖不可用（系统处于降级态，仍可服务） */
    DEGRADED,

    /** 至少一个关键依赖不可用（不应继续接流量） */
    DOWN
}
