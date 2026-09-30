package com.pivotos.starter.search.route;

import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.spi.SearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 搜索实现路由工厂。
 * <p>与 {@code KbVectorStoreFactory} 同口径：收集容器里全部 {@link SearchProvider} Bean，
 * 按 {@code pivotos.search.type} 路由；<b>未命中不抛异常，而是回落到 simple 并打 WARN</b>——
 * 保证「配错也能起服」（无 ES 环境可起服是本 Sprint 的硬要求）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public class SearchProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderFactory.class);

    private final SearchProviderType configuredType;
    private final Map<SearchProviderType, SearchProvider> providers;
    private final SearchProvider effective;
    private final boolean fallback;

    public SearchProviderFactory(List<SearchProvider> providers, SearchProperties properties) {
        Map<SearchProviderType, SearchProvider> map = new EnumMap<>(SearchProviderType.class);
        if (providers != null) {
            for (SearchProvider p : providers) {
                map.put(p.type(), p);
            }
        }
        this.providers = map;
        this.configuredType = SearchProviderType.of(properties == null ? null : properties.getType());

        SearchProvider chosen = configuredType == null ? null : map.get(configuredType);
        if (chosen == null) {
            chosen = map.get(SearchProviderType.SIMPLE);
            this.fallback = true;
            log.warn("[PivotOS] 搜索实现未命中：type={}（已注册 {}）；已回落到 simple 内存实现，"
                            + "检索结果仅单进程可见且重启即失。生产请引入 pivotos-starter-search-easy-es 或 -es-java 并配置对应 ES 地址。",
                    properties == null ? null : properties.getType(),
                    map.keySet().stream().map(SearchProviderType::getCode).collect(Collectors.toList()));
        } else {
            this.fallback = false;
            log.info("[PivotOS] 搜索实现：type={}（已注册实现 {}）", configuredType.getCode(),
                    map.keySet().stream().map(SearchProviderType::getCode).collect(Collectors.toList()));
        }
        if (chosen == null) {
            throw new IllegalStateException("未注册任何 SearchProvider，搜索 Starter 无法工作；"
                    + "若不需要搜索能力，请设置 pivotos.search.enabled=false。");
        }
        this.effective = chosen;
    }

    /**
     * 当前生效的实现
     */
    public SearchProvider get() {
        return effective;
    }

    /**
     * 当前生效的类型（可能与配置不同——回落时会变成 SIMPLE）
     */
    public SearchProviderType effectiveType() {
        return effective.type();
    }

    /**
     * 是否发生了回落（配置的实现不可用）
     */
    public boolean isFallback() {
        return fallback;
    }

    /**
     * 已注册实现清单（日志/运维核对用）
     */
    public List<SearchProviderType> registeredTypes() {
        return new ArrayList<>(providers.keySet());
    }
}
