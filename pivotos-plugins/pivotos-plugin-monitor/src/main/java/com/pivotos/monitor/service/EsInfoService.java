package com.pivotos.monitor.service;

import com.pivotos.monitor.domain.vo.EsInfoVO;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.health.SearchHealthSnapshot;
import com.pivotos.starter.search.api.health.SearchUnavailableReason;
import com.pivotos.starter.search.api.spi.SearchHealthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ES 监控采集（系统监控 · ES 监控页）。
 *
 * <h3>依赖方向（ArchUnit A1/A2）</h3>
 * monitor 只引 {@code pivotos-starter-search-api} 契约包，经 {@link ObjectProvider} 拿
 * {@link SearchHealthProvider} Bean——<b>不认识也不依赖任何 ES 客户端实现模块</b>。
 * 没引实现模块时拿不到 Bean，走降级文案而不是启动失败（同 S121「配错也能起服」口径）。
 *
 * <h3>降级口径（硬性）</h3>
 * simple / 未启用 / 实现未引入 / 连接不可达，<b>一律返回 {@code available=false} + 原因文案，
 * 绝不抛异常</b>：监控页是只读旁路，ES 挂了要能在页面上看出「ES 挂了」，而不是拿到 HTTP 500。
 * <p>外网 ES（如 test 环境 10.0.0.7 内网地址）不可达是常态，这条口径是刚需不是防御性编程。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Service
public class EsInfoService {

    private static final Logger log = LoggerFactory.getLogger(EsInfoService.class);

    private final ObjectProvider<SearchHealthProvider> healthProviders;
    private final ObjectProvider<SearchProperties> searchProperties;

    public EsInfoService(ObjectProvider<SearchHealthProvider> healthProviders,
                         ObjectProvider<SearchProperties> searchProperties) {
        this.healthProviders = healthProviders;
        this.searchProperties = searchProperties;
    }

    public EsInfoVO collect() {
        SearchProperties properties = searchProperties.getIfAvailable();
        String configured = properties == null ? null : properties.getType();
        try {
            if (properties == null || !properties.isEnabled()) {
                // 搜索 Starter 没装配（模块未引入）或显式关闭
                return vo(SearchHealthSnapshot.unavailable(null, configured, false,
                        SearchUnavailableReason.NOT_ENABLED, null));
            }
            SearchProviderType configuredType = SearchProviderType.of(configured);
            SearchHealthProvider provider = resolve(configuredType);
            if (provider == null) {
                // 配置就是 simple → 本来就没什么可监控的；配了 ES 却拿不到实现 → 已回落 simple
                boolean fallback = isEsConfigured(configured);
                return vo(SearchHealthSnapshot.unavailable(SearchProviderType.SIMPLE.getCode(), configured, fallback,
                        fallback ? SearchUnavailableReason.FALLBACK : SearchUnavailableReason.SIMPLE_IMPL,
                        fallback ? "未引入 ES 实现模块，或客户端在启动期构建失败" : null));
            }
            return vo(provider.collect());
        } catch (Exception e) {
            // 兜底：采集链路任何异常都落成降级快照，不让页面 500
            log.warn("[PivotOS][monitor] ES 监控采集异常，已降级：{}", e.getMessage());
            return vo(SearchHealthSnapshot.unavailable(null, configured, false,
                    SearchUnavailableReason.COLLECT_FAILED, e.getMessage()));
        }
    }

    // ==================== 内部 ====================

    /**
     * 挑选健康 Provider：优先与 {@code pivotos.search.type} 匹配的那个。
     * <p>容器里正常只存在一个（实现模块的自动装配按 type 条件注册）；这里显式按配置挑选，
     * 是为了将来同时引入多个实现模块时行为依然确定（否则 ObjectProvider 的选取顺序不保证）。
     */
    private SearchHealthProvider resolve(SearchProviderType configuredType) {
        List<SearchHealthProvider> all = healthProviders.orderedStream().toList();
        if (all.isEmpty()) {
            return null;
        }
        if (configuredType != null) {
            for (SearchHealthProvider provider : all) {
                if (provider.type() == configuredType) {
                    return provider;
                }
            }
        }
        return all.get(0);
    }

    /** 配置值是否是「非 simple」（含配了不存在的实现名——那也是配了 ES，同样会回落） */
    private boolean isEsConfigured(String configured) {
        return configured != null && !configured.isBlank()
                && !SearchProviderType.SIMPLE.getCode().equalsIgnoreCase(configured.trim());
    }

    private EsInfoVO vo(SearchHealthSnapshot snapshot) {
        return new EsInfoVO(snapshot);
    }
}
