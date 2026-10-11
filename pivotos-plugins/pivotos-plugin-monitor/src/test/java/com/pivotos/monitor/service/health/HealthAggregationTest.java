package com.pivotos.monitor.service.health;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 探活汇总口径：什么时候 DOWN（503）、什么时候 DEGRADED（200）。
 *
 * <p>这是 A2 的**核心契约**，单测必须钉死——线上 Nginx 就按它摘实例：
 * 判松了故障实例继续接流量，判严了一个 Redis 抖动就把整站摘空。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
class HealthAggregationTest {

    @Test
    void allUpIsUpWithHttp200() {
        HealthSnapshot snapshot = HealthSnapshot.aggregate(List.of(up("db"), up("redis"), up("search")));

        assertEquals(HealthStatus.UP, snapshot.getStatus());
        assertEquals(HttpStatus.OK, snapshot.httpStatus());
        assertEquals(Map.of("db", true, "redis", true, "search", true), snapshot.getChecks());
    }

    @Test
    void criticalDownIsDownWithHttp503() {
        HealthSnapshot snapshot = HealthSnapshot.aggregate(List.of(down("db", true), up("redis"), up("search")));

        assertEquals(HealthStatus.DOWN, snapshot.getStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, snapshot.httpStatus());
        assertFalse(snapshot.getChecks().get("db"));
    }

    @Test
    void nonCriticalDownIsDegradedWithHttp200() {
        // 检索配了 ES 却拿不到实现 → 已回落 simple → 只能降级，不能摘实例
        HealthSnapshot snapshot = HealthSnapshot.aggregate(List.of(up("db"), up("redis"), down("search", false)));

        assertEquals(HealthStatus.DEGRADED, snapshot.getStatus());
        assertEquals(HttpStatus.OK, snapshot.httpStatus());
        assertFalse(snapshot.getChecks().get("search"));
        assertTrue(snapshot.getChecks().get("db"));
    }

    @Test
    void absentDependencyIsIgnored() {
        // 没引 Redis 的系统：缺席项必须既不判降级也不判 DOWN
        HealthSnapshot snapshot = HealthSnapshot.aggregate(List.of(up("db"), absent("redis"), up("search")));

        assertEquals(HealthStatus.UP, snapshot.getStatus());
        assertEquals(HttpStatus.OK, snapshot.httpStatus());
    }

    @Test
    void criticalWinsOverDegraded() {
        HealthSnapshot snapshot = HealthSnapshot.aggregate(List.of(down("db", true), down("search", false)));

        assertEquals(HealthStatus.DOWN, snapshot.getStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, snapshot.httpStatus());
    }

    @Test
    void checksKeepInsertionOrderForReadableProbeOutput() {
        HealthSnapshot snapshot = HealthSnapshot.aggregate(List.of(up("db"), up("redis"), up("search")));

        assertEquals(List.of("db", "redis", "search"), List.copyOf(snapshot.getChecks().keySet()));
    }

    private static HealthCheckResult up(String name) {
        return HealthCheckResult.builder().name(name).critical(true).up(true).build();
    }

    private static HealthCheckResult down(String name, boolean critical) {
        return HealthCheckResult.builder().name(name).critical(critical).up(false)
                .reason("测试用故障").error("RuntimeException").build();
    }

    private static HealthCheckResult absent(String name) {
        return HealthCheckResult.absent(name, true, "依赖未装配");
    }
}
