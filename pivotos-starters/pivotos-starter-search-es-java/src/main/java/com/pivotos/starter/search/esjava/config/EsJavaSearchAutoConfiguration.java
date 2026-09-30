package com.pivotos.starter.search.esjava.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.esjava.EsJavaSearchProvider;
import com.pivotos.starter.search.esjava.client.EsJavaClientFactory;
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
        log.info("[PivotOS] 搜索实现：es-java（官方客户端 8.19.x），节点 {}",
                properties.getEsJava() == null ? List.of() : properties.getEsJava().getUris());
        return EsJavaClientFactory.create(properties.getEsJava());
    }

    @Bean
    @ConditionalOnMissingBean(EsJavaSearchProvider.class)
    public EsJavaSearchProvider esJavaSearchProvider(ElasticsearchClient client, SearchProperties properties) {
        log.info("[PivotOS] 搜索 Provider 已注册：{}", SearchProviderType.ES_JAVA.getCode());
        return new EsJavaSearchProvider(client, properties.getEsJava() != null
                && properties.getEsJava().isRefreshOnWrite());
    }
}
