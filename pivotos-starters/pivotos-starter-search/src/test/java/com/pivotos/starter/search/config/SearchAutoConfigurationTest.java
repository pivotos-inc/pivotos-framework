package com.pivotos.starter.search.config;

import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.spi.SearchProvider;
import com.pivotos.starter.search.api.template.SearchTemplate;
import com.pivotos.starter.search.fixture.OperLogDoc;
import com.pivotos.starter.search.route.SearchProviderFactory;
import com.pivotos.starter.search.simple.SimpleSearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配：验证「默认开、配错回落、显式关」三种形态，以及无 ES 环境下容器能正常起来。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class SearchAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SearchAutoConfiguration.class));

    @Test
    void shouldEnableByDefaultWithSimpleProvider() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SearchProperties.class);
            assertThat(context).hasSingleBean(SimpleSearchProvider.class);
            assertThat(context).hasSingleBean(SearchProviderFactory.class);
            assertThat(context).hasSingleBean(SearchTemplate.class);
            SearchTemplate template = context.getBean(SearchTemplate.class);
            assertThat(template.type()).isEqualTo(SearchProviderType.SIMPLE);
        });
    }

    @Test
    void shouldBindConfigurationKeys() {
        runner.withPropertyValues(
                        "pivotos.search.type=simple",
                        "pivotos.search.index-prefix=dev",
                        "pivotos.search.es-java.uris[0]=http://es:9200")
                .run(context -> {
                    SearchProperties props = context.getBean(SearchProperties.class);
                    assertThat(props.getType()).isEqualTo("simple");
                    assertThat(props.getIndexPrefix()).isEqualTo("dev");
                    assertThat(props.getEsJava().getUris()).containsExactly("http://es:9200");
                });
    }

    @Test
    void shouldFallbackToSimpleWhenConfiguredTypeUnavailable() {
        // 典型场景：只引了主 Starter，却把 type 配成了未引入的 es-java
        runner.withPropertyValues("pivotos.search.type=es-java")
                .run(context -> {
                    SearchProviderFactory factory = context.getBean(SearchProviderFactory.class);
                    assertThat(factory.isFallback()).isTrue();
                    assertThat(factory.effectiveType()).isEqualTo(SearchProviderType.SIMPLE);
                    assertThat(context.getBean(SearchTemplate.class).type())
                            .isEqualTo(SearchProviderType.SIMPLE);
                });
    }

    @Test
    void shouldRouteToRegisteredProviderWhenPresent() {
        runner.withPropertyValues("pivotos.search.type=es-java")
                .withBean(FakeEsJavaProvider.class, FakeEsJavaProvider::new)
                .run(context -> {
                    List<SearchProvider> providers = List.copyOf(context.getBeansOfType(SearchProvider.class).values());
                    assertThat(providers).hasSize(2);
                    SearchProviderFactory factory = context.getBean(SearchProviderFactory.class);
                    assertThat(factory.isFallback()).isFalse();
                    assertThat(factory.effectiveType()).isEqualTo(SearchProviderType.ES_JAVA);
                });
    }

    @Test
    void shouldNotAssembleWhenDisabled() {
        runner.withPropertyValues("pivotos.search.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SearchProperties.class);
                    assertThat(context).doesNotHaveBean(SearchTemplate.class);
                    assertThat(context).doesNotHaveBean(SimpleSearchProvider.class);
                });
    }

    @Test
    void shouldWorkEndToEndInContainer() {
        // 无 ES 环境下的完整闭环：容器起来后可直接用门面写入并检索
        runner.run(context -> {
            SearchTemplate template = context.getBean(SearchTemplate.class);
            template.index(new OperLogDoc(1L, "登录成功", 0, true, null));
            assertThat(template.count(com.pivotos.starter.search.api.query.LambdaSearchQuery
                    .of(OperLogDoc.class))).isEqualTo(1);
        });
    }

    /**
     * 模拟实现模块注册的 Provider（不引入任何 ES 依赖）
     */
    static class FakeEsJavaProvider implements SearchProvider {

        @Override
        public SearchProviderType type() {
            return SearchProviderType.ES_JAVA;
        }

        @Override
        public void index(com.pivotos.starter.search.api.document.SearchDocument document) {
            // 测试桩
        }

        @Override
        public void indexBatch(List<com.pivotos.starter.search.api.document.SearchDocument> documents) {
            // 测试桩
        }

        @Override
        public void delete(String indexName, String id) {
            // 测试桩
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
            return true;
        }

        @Override
        public void createIndexIfAbsent(String indexName) {
            // 测试桩
        }
    }
}
