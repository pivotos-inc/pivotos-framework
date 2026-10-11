package com.pivotos.monitor.service;

import com.pivotos.monitor.config.OpsHealthProperties;
import com.pivotos.monitor.service.health.HealthCheckResult;
import com.pivotos.monitor.service.health.HealthCheckStep;
import com.pivotos.monitor.service.health.HealthSnapshot;
import com.pivotos.monitor.service.health.HealthStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 探活服务的兜底口径：<b>探针自己绝不能 500</b>。
 *
 * <p>{@link HealthCheckStep} 的契约是「自己兜住异常」，但契约挡不住新加检查项的人忘了 try——
 * 这里替他们兜一层并记 WARN 而不是抛出；否则 Nginx 探活拿到 500，整个集群被摘空，
 * 而根因只是一个没写 try 的新检查项。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
class OpsHealthServiceTest {

    @Test
    void throwingStepBecomesDownInsteadOfBlowingUpTheProbe() {
        OpsHealthService service = new OpsHealthService(props(3000), List.of(new ThrowingStep("db", true)));

        HealthSnapshot snapshot = service.probe();

        assertEquals(HealthStatus.DOWN, snapshot.getStatus());
        HealthCheckResult result = snapshot.getDetails().get(0);
        assertFalse(result.isUp());
        assertNotNull(result.getError(), "异常类名必须留下，否则排障无从下手");
        assertTrue(result.getReason().contains("抛出异常"));
    }

    @Test
    void absentStepIsSkippedWithoutPollutingStatus() {
        OpsHealthService service = new OpsHealthService(props(3000), List.of(new AbsentStep("redis")));

        HealthSnapshot snapshot = service.probe();

        assertEquals(HealthStatus.UP, snapshot.getStatus());
        assertTrue(snapshot.getDetails().get(0).isAbsent());
    }

    @Test
    void stepReturningNullIsTreatedAsFailure() {
        OpsHealthService service = new OpsHealthService(props(3000), List.of(new NullStep("db", true)));

        assertEquals(HealthStatus.DOWN, service.probe().getStatus());
    }

    @Test
    void timeoutBudgetIsClampedToAtLeast200ms() {
        AtomicInteger seen = new AtomicInteger();
        OpsHealthService service = new OpsHealthService(props(10), List.of(new RecordingStep("db", seen)));

        service.probe();

        assertEquals(200, seen.get(), "预算下限保护：配成 10ms 也至少给 200ms，避免探活本身把依赖打挂再误判成故障");
    }

    @Test
    void configuredBudgetIsPassedThroughUnchanged() {
        AtomicInteger seen = new AtomicInteger();
        OpsHealthService service = new OpsHealthService(props(4500), List.of(new RecordingStep("db", seen)));

        service.probe();

        assertEquals(4500, seen.get());
    }

    @Test
    void searchDegradedAloneKeepsHttp200() {
        OpsHealthService service = new OpsHealthService(props(3000),
                List.of(new OkStep("db", true), new OkStep("redis", true), new DownStep("search", false)));

        HealthSnapshot snapshot = service.probe();

        assertEquals(HealthStatus.DEGRADED, snapshot.getStatus());
        assertEquals(200, snapshot.httpStatus().value());
    }

    private static OpsHealthProperties props(int timeoutMs) {
        OpsHealthProperties properties = new OpsHealthProperties();
        properties.setTimeoutMs(timeoutMs);
        return properties;
    }

    private static class ThrowingStep implements HealthCheckStep {
        private final String name;
        private final boolean critical;

        ThrowingStep(String name, boolean critical) {
            this.name = name;
            this.critical = critical;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean critical() {
            return critical;
        }

        @Override
        public HealthCheckResult probe(int timeoutMs) {
            throw new IllegalStateException("检查项实现忘记 try 了");
        }
    }

    private static class NullStep implements HealthCheckStep {
        private final String name;
        private final boolean critical;

        NullStep(String name, boolean critical) {
            this.name = name;
            this.critical = critical;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean critical() {
            return critical;
        }

        @Override
        public HealthCheckResult probe(int timeoutMs) {
            return null;
        }
    }

    private static class AbsentStep implements HealthCheckStep {
        private final String name;

        AbsentStep(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean critical() {
            return true;
        }

        @Override
        public boolean absent() {
            return true;
        }

        @Override
        public HealthCheckResult probe(int timeoutMs) {
            throw new IllegalStateException("缺席项不该被执行");
        }
    }

    private static class RecordingStep implements HealthCheckStep {
        private final String name;
        private final AtomicInteger seen;

        RecordingStep(String name, AtomicInteger seen) {
            this.name = name;
            this.seen = seen;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean critical() {
            return true;
        }

        @Override
        public HealthCheckResult probe(int timeoutMs) {
            seen.set(timeoutMs);
            return HealthCheckResult.builder().name(name).critical(true).up(true).build();
        }
    }

    private static class OkStep implements HealthCheckStep {
        private final String name;
        private final boolean critical;

        OkStep(String name, boolean critical) {
            this.name = name;
            this.critical = critical;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean critical() {
            return critical;
        }

        @Override
        public HealthCheckResult probe(int timeoutMs) {
            return HealthCheckResult.builder().name(name).critical(critical).up(true).build();
        }
    }

    private static class DownStep implements HealthCheckStep {
        private final String name;
        private final boolean critical;

        DownStep(String name, boolean critical) {
            this.name = name;
            this.critical = critical;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean critical() {
            return critical;
        }

        @Override
        public HealthCheckResult probe(int timeoutMs) {
            return HealthCheckResult.builder().name(name).critical(critical).up(false)
                    .fallback(true).reason("已回落").build();
        }
    }
}
