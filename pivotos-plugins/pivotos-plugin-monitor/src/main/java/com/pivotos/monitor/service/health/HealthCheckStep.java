package com.pivotos.monitor.service.health;

/**
 * 依赖探活项。
 *
 * <h3>实现纪律（硬性）</h3>
 * <ol>
 *   <li><b>绝不抛异常</b>——不可达 / 超时 / 未装配一律落成 {@code up=false} 的结果，
 *       由 {@link OpsHealthService} 统一汇总；探针是只读旁路，不能自己先崩。</li>
 *   <li><b>必须受 {@code timeoutMs} 预算约束</b>——每一项都要能把超时吃在自己身上
 *       （JDBC {@code setQueryTimeout}、Redisson {@code ping(timeout)}）；
 *       搜索项刻意只读启动期缓存的 {@code isAvailable()}，<b>不发网络请求</b>
 *       （见 {@code SearchHealthProvider} 契约：启动期一次性探测，运行期重复打只会拖慢探针）。</li>
 *   <li>依赖 Bean 不存在时用 {@link #absent()} 返回 true，该项<b>不参与 ready 结论</b>
 *       （没引 Redis 的系统不该被判 DOWN）。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
public interface HealthCheckStep {

    /** 检查项名：db / redis / search */
    String name();

    /**
     * 是否关键依赖。关键项不可用 → 整体 {@link HealthStatus#DOWN}（503）。
     * <p>默认口径：<b>db / redis 关键，search 非关键</b>——检索配错会回落 simple，系统仍可用；
     * 而 DB 与 Redis（Sa-Token 会话落在 Redis）不可用则服务已不可用。
     */
    boolean critical();

    /** 运行时有没有这个依赖（没装配 → true，该项跳过） */
    default boolean absent() {
        return false;
    }

    /** 执行探活，受 {@code timeoutMs} 约束，不抛异常 */
    HealthCheckResult probe(int timeoutMs);
}
