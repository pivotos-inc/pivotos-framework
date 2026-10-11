package com.pivotos.monitor.support;

import com.pivotos.monitor.service.health.HealthCheckResult;
import com.pivotos.monitor.service.health.HealthSnapshot;
import com.pivotos.monitor.service.health.HealthStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匿名可见面裁剪（A2 的**安全红线**）：匿名只能拿到 status + checks，明细必须过登录。
 *
 * <p>之所以要为这么点逻辑写单测：Git 历史里「顺手多返回一个字段」是最高频的泄漏来源，
 * 而探针恰恰是唯一免鉴权的端点——这里钉死，等于给以后的手滑上了锁。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
class HealthPayloadTest {

    private final MockEnvironment environment = new MockEnvironment();

    @Test
    void anonymousReadyBodyHasOnlyStatusAndChecks() {
        Map<String, Object> body = HealthPayload.ready(snapshot(HealthStatus.DEGRADED), false, environment);

        assertEquals(List.of("status", "checks"), List.copyOf(body.keySet()));
        assertEquals("DEGRADED", body.get("status"));
        assertFalse(body.toString().contains("HikariDataSource"), "匿名响应不得带实现名");
        assertFalse(body.toString().contains("es-java"), "匿名响应不得带配置值");
    }

    @Test
    void anonymousLiveBodyHasOnlyStatus() {
        Map<String, Object> body = HealthPayload.live(false, environment);

        assertEquals(List.of("status"), List.copyOf(body.keySet()));
        assertEquals("UP", body.get("status"));
    }

    @Test
    void detailedBodyAddsDetailsAndApp() {
        Map<String, Object> body = HealthPayload.ready(snapshot(HealthStatus.DOWN), true, environment);

        assertEquals(List.of("status", "checks", "details", "app"), List.copyOf(body.keySet()));
        assertTrue(body.get("details") instanceof List);
        Map<?, ?> app = (Map<?, ?>) body.get("app");
        assertTrue(app.containsKey("uptimeSeconds"));
        assertTrue(app.containsKey("activeProfiles"));
        assertTrue(app.containsKey("application"));
    }

    @Test
    void appInfoNeverLeaksCommandLineOrHosts() {
        Map<String, Object> app = AppRuntimeInfo.snapshot(environment);

        assertEquals(List.of("uptimeSeconds", "activeProfiles", "application"), List.copyOf(app.keySet()),
                "运行态信息刻意只留这三项：多了就是部署画像泄漏");
    }

    private static HealthSnapshot snapshot(HealthStatus status) {
        HealthSnapshot snapshot = new HealthSnapshot();
        snapshot.setStatus(status);
        snapshot.getChecks().put("db", true);
        snapshot.setDetails(List.of(HealthCheckResult.builder()
                .name("db").critical(true).up(true)
                .implementation("HikariDataSource").configuredType("mysql").build()));
        return snapshot;
    }
}
