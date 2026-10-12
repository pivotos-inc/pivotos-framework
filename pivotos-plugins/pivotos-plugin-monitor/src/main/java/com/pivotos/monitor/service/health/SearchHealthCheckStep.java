package com.pivotos.monitor.service.health;

import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.spi.SearchHealthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/**
 * 检索探活（非关键依赖）：只读 {@code SearchHealthProvider#isAvailable()} 的启动期结论。
 *
 * <h3>为什么是非关键</h3>
 * 检索配了 ES 但拿不到实现 / 启动期自检没过，整条链路会回落 simple（内存+快照），
 * 业务仍可服务——这时判 DOWN 会把实例从负载均衡摘掉，得不偿失。故只标 DEGRADED，
 * 且必须把「配的是 es-java、跑的是 simple」这一回落事实写进明细（S127-B 的三件套口径）。
 *
 * <h3>为什么运行期不真连 ES</h3>
 * {@code SearchHealthProvider#collect()} 有可能发起网络请求，而探针会被 Nginx 高频轮询；
 * 这里的结论与 ES 监控页共用同一个「启动期一次性探测」结果，避免探活把 ES 拖慢。
 * <b>代价：ES 恢复后需要重启进程才会翻绿</b>——沿用 S127-B 既有口径，不在本轮偷偷改。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@RequiredArgsConstructor
public class SearchHealthCheckStep implements HealthCheckStep {

    public static final String NAME = "search";

    private final ObjectProvider<SearchHealthProvider> providerRegistry;
    private final ObjectProvider<SearchProperties> propertiesProvider;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean critical() {
        return false;
    }

    @Override
    public boolean absent() {
        SearchProperties properties = propertiesProvider.getIfAvailable();
        return properties == null || !properties.isEnabled();
    }

    @Override
    public HealthCheckResult probe(int timeoutMs) {
        SearchProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null) {
            return HealthCheckResult.absent(NAME, false, "未装配检索模块");
        }
        String configured = properties.getType();
        if (!properties.isEnabled()) {
            return HealthCheckResult.absent(NAME, false, "pivotos.search.enabled=false，检索未启用");
        }
        long start = System.nanoTime();
        SearchProviderType configuredType = SearchProviderType.of(configured);
        SearchHealthProvider provider = resolve(configuredType);
        // 配了 ES（或配了不存在的实现名——同样是「想要 ES」）却拿到 simple → 已经回落
        boolean esConfigured = configuredType != null && configuredType != SearchProviderType.SIMPLE;
        if (provider == null) {
            if (!esConfigured) {
                return HealthCheckResult.builder()
                        .name(NAME).critical(false).up(true)
                        .implementation(SearchProviderType.SIMPLE.getCode())
                        .configuredType(SearchProviderType.SIMPLE.getCode())
                        .reason("本地 simple 检索（进程内），非集群方案")
                        .durationMs(Elapsed.since(start))
                        .build();
            }
            return HealthCheckResult.builder()
                    .name(NAME).critical(false).up(false).fallback(true)
                    .implementation(SearchProviderType.SIMPLE.getCode())
                    .configuredType(configured)
                    .reason("未引入 ES 实现模块或客户端构建失败，已回落 simple")
                    .durationMs(Elapsed.since(start))
                    .build();
        }
        boolean available = provider.isAvailable();
        String implementation = provider.type() == null ? null : provider.type().getCode();
        boolean fallback = esConfigured && provider.type() == SearchProviderType.SIMPLE;
        return HealthCheckResult.builder()
                .name(NAME).critical(false).up(available).fallback(fallback || !available)
                .implementation(implementation)
                .configuredType(configured)
                .reason(available ? null : "ES 客户端启动期自检未通过，已回落 simple（ES 恢复后需重启进程）")
                .durationMs(Elapsed.since(start))
                .build();
    }

    /** 挑选健康 Provider：优先与 pivotos.search.type 匹配的那个（ObjectProvider 顺序不保证） */
    private SearchHealthProvider resolve(SearchProviderType configuredType) {
        List<SearchHealthProvider> all = providerRegistry.orderedStream().toList();
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
        return configuredType == null ? all.get(0) : null;
    }
}
