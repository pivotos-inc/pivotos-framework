package com.pivotos.starter.cloud.sc.circuitbreaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 熔断计数的口径测试：计数是「熔断真的拦住了请求」这类断言的底座，本身也要可验证 */
class CloudCircuitBreakerStatsTest {

    @Test
    void 按名称分桶并支持全量与求和() {
        CloudCircuitBreakerStats stats = new CloudCircuitBreakerStats();
        stats.recordCall("a");
        stats.recordSuccess("a");
        stats.recordCall("a");
        stats.recordFailure("a");
        stats.recordCall("b");
        stats.recordNotPermitted("b");
        stats.recordFallback("b");

        assertThat(stats.snapshot("a").calls()).isEqualTo(2);
        assertThat(stats.snapshot("a").successes()).isEqualTo(1);
        assertThat(stats.snapshot("a").failures()).isEqualTo(1);
        assertThat(stats.snapshot("b").notPermitted()).isEqualTo(1);
        assertThat(stats.snapshot("b").fallbacks()).isEqualTo(1);

        assertThat(stats.snapshotAll()).containsOnlyKeys("a", "b");
        assertThat(stats.total().calls()).isEqualTo(3);
        assertThat(stats.total().notPermitted()).isEqualTo(1);
    }

    @Test
    void 未知名称返回空快照而不是null() {
        CloudCircuitBreakerStats stats = new CloudCircuitBreakerStats();
        assertThat(stats.snapshot("nope").calls()).isZero();
        assertThat(stats.snapshotAll()).isEmpty();
    }

    @Test
    void 快照是值拷贝() {
        CloudCircuitBreakerStats stats = new CloudCircuitBreakerStats();
        stats.recordCall("a");
        CloudCircuitBreakerStats.Snapshot snapshot = stats.snapshot("a");
        stats.recordCall("a");
        assertThat(snapshot.calls()).as("快照取的是当时的值，不随后续调用漂移").isEqualTo(1);
    }

    @Test
    void reset清空全部计数() {
        CloudCircuitBreakerStats stats = new CloudCircuitBreakerStats();
        stats.recordCall("a");
        stats.reset();
        assertThat(stats.total().calls()).isZero();
    }
}
