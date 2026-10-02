package com.pivotos.starter.search.esjava.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.spi.SearchHealthProvider;
import com.pivotos.starter.search.esjava.EsJavaSearchProvider;
import com.pivotos.starter.search.esjava.client.EsJavaClientFactory;
import com.pivotos.starter.search.esjava.health.EsJavaSearchHealthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * elasticsearch-java 实现自动装配。
 * <p>装配条件：{@code pivotos.search.type=es-java} 且 classpath 存在官方客户端。
 * <b>客户端构造失败（ES 不可达/未配置）时该 Bean 不注册</b>，主 Starter 会自动回落到 simple 兜底
 * ——保证无 ES 环境照样能起服。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@AutoConfiguration
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnProperty(prefix = "pivotos.search", name = "type", havingValue = "es-java")
public class EsJavaSearchAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EsJavaSearchAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ElasticsearchClient elasticsearchClient(SearchProperties properties) {
        SearchProperties.EsJava config = properties.getEsJava();
        log.info("[PivotOS] 搜索实现：es-java（官方客户端 8.19.x），节点 {}，兼容模式={}",
                config == null ? List.of() : config.getUris(),
                config != null && config.isCompatibilityMode());
        return EsJavaClientFactory.create(config);
    }

    @Bean
    @ConditionalOnMissingBean(EsJavaSearchProvider.class)
    public EsJavaSearchProvider esJavaSearchProvider(ElasticsearchClient client, SearchProperties properties) {
        // 构造时探测服务端版本；不可用（版本不在支持区间 / 不可达 / 兼容头冲突）不会抛异常，
        // 而是标记 isAvailable=false，由 SearchProviderFactory 回落到 simple 并打 WARN
        EsJavaSearchProvider provider = new EsJavaSearchProvider(client, properties.getEsJava() != null
                && properties.getEsJava().isRefreshOnWrite());
        log.info("[PivotOS] 搜索 Provider 已注册：{}（服务端 {}，可用={}）",
                SearchProviderType.ES_JAVA.getCode(), provider.serverVersion().raw(), provider.isAvailable());
        return provider;
    }

    /**
     * ES 健康采集（系统监控 · ES 监控页）。
     * <p>契约在 {@code starter-search-api} 的 {@link SearchHealthProvider}，monitor 插件只认契约，
     * 因此它不会（也不允许）依赖本实现模块；没引本模块时 monitor 侧拿不到该 Bean，走降级文案。
     */
    @Bean
    @ConditionalOnMissingBean(SearchHealthProvider.class)
    public SearchHealthProvider esJavaSearchHealthProvider(ElasticsearchClient client,
                                                           EsJavaSearchProvider provider) {
        return new EsJavaSearchHealthProvider(client, provider.isAvailable(), provider.serverVersion().raw());
    }
}
