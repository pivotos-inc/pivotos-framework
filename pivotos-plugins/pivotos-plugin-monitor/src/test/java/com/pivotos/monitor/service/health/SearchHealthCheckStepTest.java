package com.pivotos.monitor.service.health;

import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.health.SearchHealthSnapshot;
import com.pivotos.starter.search.api.spi.SearchHealthProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索探活的降级口径（沿用 S127-B「implementation / configuredType / fallback 三件套」）。
 *
 * <p>只给 {@code up=false} 而不给这三项，运维看不出「配了 es-java 却跑在 simple 上」——
 * 那正是历史上最难归因的一类事故（配错也能起服，零报错、零信号）。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
class SearchHealthCheckStepTest {

    @Test
    void simpleConfiguredIsUpAndExplicitlyNotFallback() {
        SearchHealthCheckStep step = step(props("simple", true), List.of());

        HealthCheckResult result = step.probe(3000);

        assertTrue(result.isUp());
        assertFalse(result.isFallback(), "压根配的就是 simple，不是回落");
        assertEquals("simple", result.getImplementation());
        assertEquals("simple", result.getConfiguredType());
        assertFalse(result.isCritical(), "检索是可降级依赖，不能牵动摘流");
    }

    @Test
    void esConfiguredWithoutImplementationIsReportedAsFallback() {
        SearchHealthCheckStep step = step(props("es-java", true), List.of());

        HealthCheckResult result = step.probe(3000);

        assertFalse(result.isUp());
        assertTrue(result.isFallback(), "配了 ES 却跑 simple：必须能被看出来");
        assertEquals("simple", result.getImplementation());
        assertEquals("es-java", result.getConfiguredType());
        assertNotNull(result.getReason());
    }

    @Test
    void unavailableEsProviderIsDegradedWithRestartHint() {
        SearchHealthCheckStep step = step(props("es-java", true), List.of(provider(SearchProviderType.ES_JAVA, false)));

        HealthCheckResult result = step.probe(3000);

        assertFalse(result.isUp());
        assertEquals("es-java", result.getImplementation());
        assertTrue(result.getReason().contains("重启"), "ES 恢复后需重启进程才会翻绿，这句提示是排障关键");
    }

    @Test
    void availableEsProviderIsUp() {
        SearchHealthCheckStep step = step(props("es-java", true), List.of(provider(SearchProviderType.ES_JAVA, true)));

        HealthCheckResult result = step.probe(3000);

        assertTrue(result.isUp());
        assertFalse(result.isFallback());
        assertEquals("es-java", result.getImplementation());
    }

    @Test
    void disabledSearchIsAbsentNotDown() {
        SearchHealthCheckStep step = step(props("es-java", false), List.of());

        assertTrue(step.absent(), "显式关掉的检索不该把系统判成降级");
    }

    @Test
    void missingSearchModuleIsAbsent() {
        assertTrue(step(null, List.of()).absent());
    }

    private static SearchHealthCheckStep step(SearchProperties properties, List<SearchHealthProvider> providers) {
        return new SearchHealthCheckStep(providerRegistry(providers), provider(properties));
    }

    private static SearchProperties props(String type, boolean enabled) {
        SearchProperties properties = new SearchProperties();
        properties.setType(type);
        properties.setEnabled(enabled);
        return properties;
    }

    private static SearchHealthProvider provider(SearchProviderType type, boolean available) {
        return new SearchHealthProvider() {
            @Override
            public SearchProviderType type() {
                return type;
            }

            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public SearchHealthSnapshot collect() {
                return SearchHealthSnapshot.unavailable(type.getCode(), type.getCode(), false, null, null);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SearchHealthProvider> providerRegistry(List<SearchHealthProvider> providers) {
        ObjectProvider<SearchHealthProvider> registry = mock(ObjectProvider.class);
        when(registry.orderedStream()).thenReturn(providers.stream());
        return registry;
    }
}
