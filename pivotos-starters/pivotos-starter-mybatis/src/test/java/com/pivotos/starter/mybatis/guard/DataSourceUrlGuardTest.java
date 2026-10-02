package com.pivotos.starter.mybatis.guard;

import com.pivotos.starter.mybatis.guard.DataSourceUrlGuard.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L9 清偿契约：数据源 URL 溯源。
 *
 * <p>核心不是「找出当年那个注入源」，而是把「生效库 / 来源键」变成每次启动都能看到的确定性输出，
 * 并提供可选的 fail-fast 断言。这些用例钉死溯源优先级、库名解析的边界形态与 fail-fast 语义。
 */
@DisplayName("L9 数据源 URL 溯源守卫")
class DataSourceUrlGuardTest {

    private static StandardEnvironment env(Map<String, Object> props) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", props));
        return environment;
    }

    // ==================== 库名解析 ====================

    @Test
    @DisplayName("databaseOf：带查询参数 / 裸路径 / 末尾斜杠 / 非法串 全部不抛且结果确定")
    void databaseOfBoundaries() {
        assertEquals("pivotos_dev",
                DataSourceUrlGuard.databaseOf("jdbc:mysql://175.24.176.176:3306/pivotos_dev"
                        + "?useUnicode=true&characterEncoding=utf8&allowMultiQueries=false"));
        assertEquals("pivotos_dev", DataSourceUrlGuard.databaseOf("jdbc:mysql://127.0.0.1:3306/pivotos_dev"));
        assertEquals("", DataSourceUrlGuard.databaseOf("jdbc:mysql://127.0.0.1:3306/"));
        assertEquals("", DataSourceUrlGuard.databaseOf("jdbc:mysql://127.0.0.1:3306"));
        assertEquals("", DataSourceUrlGuard.databaseOf("not-a-jdbc-url"));
        assertEquals("", DataSourceUrlGuard.databaseOf(null));
        assertEquals("", DataSourceUrlGuard.databaseOf("   "));
    }

    // ==================== 溯源优先级 ====================

    @Test
    @DisplayName("resolve：dynamic master 优先于单数据源 url")
    void dynamicMasterWins() {
        Report report = DataSourceUrlGuard.resolve(env(Map.of(
                "spring.datasource.dynamic.datasource.master.url", "jdbc:mysql://h:3306/pivotos_dev",
                "spring.datasource.url", "jdbc:mysql://h:3306/pivotos_asm")));

        assertTrue(report.resolved());
        assertEquals("pivotos_dev", report.database());
        assertEquals("spring.datasource.dynamic.datasource.master.url", report.sourceKey());
    }

    @Test
    @DisplayName("resolve：只有单数据源时回退到 spring.datasource.url")
    void fallBackToSingleDatasource() {
        Report report = DataSourceUrlGuard.resolve(env(Map.of(
                "spring.datasource.url", "jdbc:mysql://h:3306/pivotos_dev")));

        assertTrue(report.resolved());
        assertEquals("pivotos_dev", report.database());
        assertEquals("spring.datasource.url", report.sourceKey());
    }

    @Test
    @DisplayName("resolve：候选键全空时 resolved=false（不臆造结论）")
    void unresolved() {
        Report report = DataSourceUrlGuard.resolve(env(Map.of("spring.datasource.username", "root")));

        assertFalse(report.resolved());
        assertEquals("", report.database());
        assertEquals("", report.sourceKey());
    }

    // ==================== fail-fast ====================

    @Test
    @DisplayName("mismatch：未配置期望库 / 一致 → 不阻断")
    void mismatchDisabledOrMatched() {
        Report report = new Report("jdbc:mysql://h:3306/pivotos_dev", "pivotos_dev",
                "spring.datasource.url", true);

        assertNull(DataSourceUrlGuard.mismatch(report, null));
        assertNull(DataSourceUrlGuard.mismatch(report, "  "));
        assertNull(DataSourceUrlGuard.mismatch(report, "pivotos_dev"));
    }

    @Test
    @DisplayName("mismatch：连错库时给出「期望 / 生效 / 来源键」三要素")
    void mismatchReportsThreeFacts() {
        Report report = new Report("jdbc:mysql://h:3306/pivotos_asm", "pivotos_asm",
                "spring.datasource.dynamic.datasource.master.url", true);

        String message = DataSourceUrlGuard.mismatch(report, "pivotos_dev");
        assertNotNull(message);
        assertTrue(message.contains("pivotos_dev"), message);
        assertTrue(message.contains("pivotos_asm"), message);
        assertTrue(message.contains("spring.datasource.dynamic.datasource.master.url"), message);
    }

    @Test
    @DisplayName("mismatch：配了期望库却压根没解析到 URL → 同样阻断（防『静默连到别的库』）")
    void mismatchOnUnresolved() {
        String message = DataSourceUrlGuard.mismatch(new Report("", "", "", false), "pivotos_dev");
        assertNotNull(message);
        assertTrue(message.contains("pivotos_dev"), message);
    }
}
