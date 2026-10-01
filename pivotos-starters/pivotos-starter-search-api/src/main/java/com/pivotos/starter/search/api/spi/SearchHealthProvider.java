package com.pivotos.starter.search.api.spi;

import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.health.SearchHealthSnapshot;

/**
 * 搜索健康信息 SPI（ES 监控，S128）。
 * <p><b>为什么要有它</b>：系统监控要展示 ES 集群指标，但 monitor 插件受 ArchUnit A1/A2 约束
 * <b>只能引 {@code pivotos-starter-search-api}</b>，不能直接依赖 {@code -es-java} 实现模块。
 * 故在契约包里声明此 SPI，由实现模块（es-java）实现并注册为 Bean，monitor 侧经
 * {@code ObjectProvider} 拿到——没引实现模块时拿不到 Bean，走降级文案而不是启动失败。
 *
 * <p><b>实现纪律</b>：
 * <ol>
 *   <li>{@link #collect()} <b>绝不抛异常</b>——不可达 / 解析失败一律返回
 *       {@code available=false} 的快照（监控是只读旁路，不能拖垮页面）；</li>
 *   <li>采集只用各 ES 大版本都支持的端点，字段名差异在实现侧做兼容（见 {@code EsHealthJsonParser}）；</li>
 *   <li>启动期自检未通过（{@link #isAvailable()}=false）时返回「已回落 simple」的降级快照，
 *       <b>不再发起网络请求</b>——探测结论是启动期缓存的，重复请求只会拖慢页面。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public interface SearchHealthProvider {

    /**
     * 实现类型（与 {@code pivotos.search.type} 对应）
     */
    SearchProviderType type();

    /**
     * 该实现<b>当前是否可用</b>（启动期自检结论，与 {@link SearchProvider#isAvailable()} 同语义）。
     * 默认 true。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 采集健康快照。<b>契约：任何情况下都不抛异常</b>，失败返回 {@code available=false} 快照。
     */
    SearchHealthSnapshot collect();
}
