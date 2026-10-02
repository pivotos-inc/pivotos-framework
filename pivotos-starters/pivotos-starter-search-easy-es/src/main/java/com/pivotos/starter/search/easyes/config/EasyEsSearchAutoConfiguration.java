package com.pivotos.starter.search.easyes.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.easyes.EasyEsSearchProvider;
import com.pivotos.starter.search.easyes.client.EasyEsClientFactory;
import com.pivotos.starter.search.easyes.client.EasyEsMapperFactory;
import org.dromara.easyes.core.kernel.BaseEsMapperImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.List;

/**
 * Easy-ES 实现自动装配。
 * <p>装配条件：{@code pivotos.search.type=easy-es} 且 classpath 存在 Easy-ES。
 * 客户端/Mapper 构造失败时该 Bean 不注册，主 Starter 自动回落到 simple 兜底。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.dromara.easyes.core.kernel.EsWrappers")
@ConditionalOnProperty(prefix = "pivotos.search", name = "type", havingValue = "easy-es")
public class EasyEsSearchAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EasyEsSearchAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ElasticsearchClient easyEsClient(SearchProperties properties) {
        log.info("[PivotOS] 搜索实现：easy-es（3.0.2 / 内嵌 es-java 7.17.28），节点 {}",
                properties.getEasyEs() == null ? List.of() : properties.getEasyEs().getUris());
        return EasyEsClientFactory.create(properties.getEasyEs());
    }

    @Bean
    @ConditionalOnMissingBean(EasyEsSearchProvider.class)
    public EasyEsSearchProvider easyEsSearchProvider(ElasticsearchClient easyEsClient) {
        BaseEsMapperImpl<HashMap> mapper = EasyEsMapperFactory.createMapper(easyEsClient);
        log.info("[PivotOS] 搜索 Provider 已注册：{}", SearchProviderType.EASY_ES.getCode());
        return new EasyEsSearchProvider(mapper, easyEsClient);
    }
}
