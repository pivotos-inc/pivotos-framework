package com.pivotos.starter.search.config;

import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.spi.SearchProvider;
import com.pivotos.starter.search.api.template.SearchTemplate;
import com.pivotos.starter.search.route.SearchProviderFactory;
import com.pivotos.starter.search.simple.SimpleSearchProvider;
import com.pivotos.starter.search.template.SearchTemplateImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * ST-SEARCH 自动装配（默认启用，type 默认 simple）。
 * <p>实现模块（easy-es / es-java）各自注册 {@link SearchProvider} Bean，由
 * {@link SearchProviderFactory} 统一路由；<b>不使用 {@code @ConditionalOnBean} 判断实现是否就绪</b>
 * ——跨自动配置的 Bean 条件存在时序窗口（S117 经验：改用属性驱动或运行期回落）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "pivotos.search", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties
public class SearchAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SearchAutoConfiguration.class);

    /**
     * 配置绑定（bean 方法级 @ConfigurationProperties：契约包保持零 Spring 依赖）
     */
    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "pivotos.search")
    public SearchProperties searchProperties() {
        return new SearchProperties();
    }

    /**
     * simple 兜底实现：恒装配。
     * 无 ES 环境 / 实现未引入 / type 配错三种情况都能靠它让应用正常起服（同 vector-store 的 simple 口径）。
     */
    @Bean
    @ConditionalOnMissingBean(SimpleSearchProvider.class)
    public SimpleSearchProvider simpleSearchProvider() {
        log.info("[PivotOS] 搜索 simple 内存实现已装配（无中间件依赖；结果单进程可见、重启即失）");
        return new SimpleSearchProvider();
    }

    /**
     * 实现路由工厂：收集全部 SearchProvider Bean（含实现模块注册的），按 pivotos.search.type 路由
     */
    @Bean
    @ConditionalOnMissingBean
    public SearchProviderFactory searchProviderFactory(List<SearchProvider> providers,
                                                       SearchProperties properties) {
        return new SearchProviderFactory(providers, properties);
    }

    /**
     * 搜索门面
     */
    @Bean
    @ConditionalOnMissingBean
    public SearchTemplate searchTemplate(SearchProviderFactory factory, SearchProperties properties) {
        return new SearchTemplateImpl(factory, properties);
    }
}
