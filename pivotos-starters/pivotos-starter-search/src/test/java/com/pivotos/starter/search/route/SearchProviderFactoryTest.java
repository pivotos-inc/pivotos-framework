package com.pivotos.starter.search.route;

import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.spi.SearchProvider;
import com.pivotos.starter.search.simple.SimpleSearchProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实现路由：核心不变量是「配错也要能起服」（回落 simple 而不是启动失败）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class SearchProviderFactoryTest {

    private static SearchProperties props(String type) {
        SearchProperties p = new SearchProperties();
        p.setType(type);
        return p;
    }

    @Test
    void shouldRouteToConfiguredType() {
        SimpleSearchProvider simple = new SimpleSearchProvider();
        FakeProvider esJava = new FakeProvider(SearchProviderType.ES_JAVA);

        SearchProviderFactory factory = new SearchProviderFactory(List.of(simple, esJava), props("es-java"));

        assertSame(esJava, factory.get());
        assertEquals(SearchProviderType.ES_JAVA, factory.effectiveType());
        assertFalse(factory.isFallback());
        assertEquals(2, factory.registeredTypes().size());
    }

    @Test
    void shouldFallbackToSimpleWhenTypeNotRegistered() {
        SimpleSearchProvider simple = new SimpleSearchProvider();

        // 只注册了 simple，却配了 es-java（未引入实现模块的典型场景）
        SearchProviderFactory factory = new SearchProviderFactory(List.of(simple), props("es-java"));

        assertSame(simple, factory.get());
        assertEquals(SearchProviderType.SIMPLE, factory.effectiveType());
        assertTrue(factory.isFallback());
    }

    @Test
    void shouldFallbackToSimpleWhenTypeIsGarbage() {
        SimpleSearchProvider simple = new SimpleSearchProvider();
        SearchProviderFactory factory = new SearchProviderFactory(List.of(simple), props("milvus"));
        assertEquals(SearchProviderType.SIMPLE, factory.effectiveType());
        assertTrue(factory.isFallback());
    }

    /**
     * 实现已注册但启动期自检未通过（ES 不可达 / 服务端版本不在支持区间 / 兼容头冲突）
     * ——与「未注册」走同一条回落路径，保证应用照常起服
     */
    @Test
    void shouldFallbackToSimpleWhenProviderIsUnavailable() {
        SimpleSearchProvider simple = new SimpleSearchProvider();
        FakeProvider esJava = new FakeProvider(SearchProviderType.ES_JAVA, false);

        SearchProviderFactory factory = new SearchProviderFactory(List.of(simple, esJava), props("es-java"));

        assertSame(simple, factory.get());
        assertEquals(SearchProviderType.SIMPLE, factory.effectiveType());
        assertTrue(factory.isFallback());
    }

    @Test
    void shouldThrowWhenNoProviderAtAll() {
        assertThrows(IllegalStateException.class,
                () -> new SearchProviderFactory(List.of(), props("simple")));
    }

    private static class FakeProvider implements SearchProvider {

        private final SearchProviderType type;
        private final boolean available;

        FakeProvider(SearchProviderType type) {
            this(type, true);
        }

        FakeProvider(SearchProviderType type, boolean available) {
            this.type = type;
            this.available = available;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public SearchProviderType type() {
            return type;
        }

        @Override
        public void index(com.pivotos.starter.search.api.document.SearchDocument document) {
            // 测试桩：不落任何数据
        }

        @Override
        public void indexBatch(List<com.pivotos.starter.search.api.document.SearchDocument> documents) {
            // 测试桩：不落任何数据
        }

        @Override
        public void delete(String indexName, String id) {
            // 测试桩：不落任何数据
        }

        @Override
        public com.pivotos.starter.search.api.document.SearchResult search(
                com.pivotos.starter.search.api.document.SearchRequest request) {
            return com.pivotos.starter.search.api.document.SearchResult.empty();
        }

        @Override
        public long count(com.pivotos.starter.search.api.document.SearchRequest request) {
            return 0;
        }

        @Override
        public boolean existsIndex(String indexName) {
            return false;
        }

        @Override
        public void createIndexIfAbsent(String indexName) {
            // 测试桩：不落任何数据
        }
    }
}
