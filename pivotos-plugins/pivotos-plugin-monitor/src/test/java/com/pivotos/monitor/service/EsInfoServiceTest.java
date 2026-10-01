package com.pivotos.monitor.service;

import com.pivotos.monitor.domain.vo.EsInfoVO;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.health.SearchHealthSnapshot;
import com.pivotos.starter.search.api.health.SearchUnavailableReason;
import com.pivotos.starter.search.api.spi.SearchHealthProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ES 监控降级口径（硬性）：simple / 未启用 / 实现未引入 / 采集抛异常，
 * <b>一律返回 available=false + 原因文案，绝不抛异常</b>。
 * <p>这是本 Sprint 的头号要求——外网 ES 不可达是常态（test 环境 10.0.0.7 内网地址），
 * 监控页必须能展示「不可达」而不是 500。
 */
class EsInfoServiceTest {

    private static SearchProperties props(String type) {
        SearchProperties p = new SearchProperties();
        p.setType(type);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SearchHealthProvider> providers(SearchHealthProvider... list) {
        ObjectProvider<SearchHealthProvider> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(List.of(list).stream());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SearchProperties> properties(SearchProperties props) {
        ObjectProvider<SearchProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(props);
        return provider;
    }

    /** ① 配置就是 simple：不算故障，但要明确告知未连 ES */
    @Test
    void simpleImplDegradesWithReason() {
        EsInfoVO vo = new EsInfoService(providers(), properties(props("simple"))).collect();
        assertFalse(vo.isAvailable());
        assertEquals(SearchProviderType.SIMPLE.getCode(), vo.getImplementation());
        assertEquals("simple", vo.getConfiguredType());
        assertFalse(vo.isFallback(), "配置即 simple，不算回落");
        assertEquals(SearchUnavailableReason.SIMPLE_IMPL.name(), vo.getReasonCode());
        assertNotNull(vo.getReason());
    }

    /** ② 配了 es-java 但实现模块未引入 / 客户端构建失败 → 判为回落 */
    @Test
    void missingEsProviderMarksFallback() {
        EsInfoVO vo = new EsInfoService(providers(), properties(props("es-java"))).collect();
        assertFalse(vo.isAvailable());
        assertTrue(vo.isFallback());
        assertEquals(SearchProviderType.SIMPLE.getCode(), vo.getImplementation());
        assertEquals(SearchUnavailableReason.FALLBACK.name(), vo.getReasonCode());
    }

    /** ③ 搜索 Starter 未装配（properties 拿不到）→ 未启用 */
    @Test
    void noPropertiesMeansNotEnabled() {
        EsInfoVO vo = new EsInfoService(providers(), properties(null)).collect();
        assertFalse(vo.isAvailable());
        assertEquals(SearchUnavailableReason.NOT_ENABLED.name(), vo.getReasonCode());
    }

    /** ④ enabled=false 显式关闭 */
    @Test
    void disabledMeansNotEnabled() {
        SearchProperties p = props("es-java");
        p.setEnabled(false);
        EsInfoVO vo = new EsInfoService(providers(), properties(p)).collect();
        assertFalse(vo.isAvailable());
        assertEquals(SearchUnavailableReason.NOT_ENABLED.name(), vo.getReasonCode());
    }

    /** ⑤ 正常链路：原样透出 Provider 的快照（字段一个都不能丢） */
    @Test
    void healthySnapshotPassedThrough() {
        SearchHealthSnapshot snapshot = new SearchHealthSnapshot();
        snapshot.setAvailable(true);
        snapshot.setImplementation(SearchProviderType.ES_JAVA.getCode());
        snapshot.setConfiguredType("es-java");
        snapshot.setStatus("green");
        snapshot.setNodeCount(1);
        snapshot.setIndexCount(2);
        snapshot.setDocCount(42L);
        snapshot.setStoreSizeBytes(2048L);

        SearchHealthProvider provider = mock(SearchHealthProvider.class);
        when(provider.type()).thenReturn(SearchProviderType.ES_JAVA);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.collect()).thenReturn(snapshot);

        EsInfoVO vo = new EsInfoService(providers(provider), properties(props("es-java"))).collect();
        assertTrue(vo.isAvailable());
        assertEquals("green", vo.getStatus());
        assertEquals(1, vo.getNodeCount());
        assertEquals(2, vo.getIndexCount());
        assertEquals(42L, vo.getDocCount());
        assertEquals(2048L, vo.getStoreSizeBytes());
    }

    /** ⑥ Provider 抛异常（模拟 ES 连接不可达）→ 落成降级快照，不外抛 */
    @Test
    void providerExceptionDegradesInsteadOfThrowing() {
        SearchHealthProvider provider = mock(SearchHealthProvider.class);
        when(provider.type()).thenReturn(SearchProviderType.ES_JAVA);
        when(provider.collect()).thenThrow(new IllegalStateException("Connection refused"));

        EsInfoVO vo = new EsInfoService(providers(provider), properties(props("es-java"))).collect();
        assertFalse(vo.isAvailable());
        assertEquals(SearchUnavailableReason.COLLECT_FAILED.name(), vo.getReasonCode());
        assertTrue(vo.getReason().contains("Connection refused"), "原因文案要带上细节：" + vo.getReason());
    }

    /** ⑦ 多个 Provider 时按配置挑选（避免 ObjectProvider 选取顺序不确定） */
    @Test
    void picksProviderMatchingConfiguredType() {
        SearchHealthProvider easyEs = mock(SearchHealthProvider.class);
        when(easyEs.type()).thenReturn(SearchProviderType.EASY_ES);
        SearchHealthProvider esJava = mock(SearchHealthProvider.class);
        when(esJava.type()).thenReturn(SearchProviderType.ES_JAVA);
        when(esJava.collect()).thenReturn(new SearchHealthSnapshot());

        EsInfoVO vo = new EsInfoService(providers(easyEs, esJava), properties(props("es-java"))).collect();
        org.mockito.Mockito.verify(esJava, org.mockito.Mockito.times(1)).collect();
        org.mockito.Mockito.verify(easyEs, org.mockito.Mockito.never()).collect();
        assertNotNull(vo);
    }

    /** ⑧ 配了不存在的实现名（配错）也按「已回落」处理，而不是当成 simple */
    @Test
    void unknownTypeNameMarksFallback() {
        EsInfoVO vo = new EsInfoService(providers(), properties(props("elasticsearch"))).collect();
        assertFalse(vo.isAvailable());
        assertTrue(vo.isFallback());
        assertEquals(SearchUnavailableReason.FALLBACK.name(), vo.getReasonCode());
    }

    /** ⑨ 降级快照也必须能被 Jackson 正常序列化（前端只认 JSON） */
    @Test
    void degradedSnapshotSerializable() {
        EsInfoVO vo = new EsInfoService(providers(), properties(props("simple"))).collect();
        assertNotNull(vo.getReason());
        assertNotNull(vo.getNodes(), "明细列表不应为 null，否则前端 v-for 会炸");
        assertNotNull(vo.getIndices());
    }

    /** ⑩ 快照拷贝构造：字段一个都不能漏（新增契约字段时必须同步这里） */
    @Test
    void copyConstructorCopiesEveryField() {
        SearchHealthSnapshot src = new SearchHealthSnapshot();
        src.setAvailable(true);
        src.setImplementation("es-java");
        src.setConfiguredType("es-java");
        src.setFallback(true);
        src.setReasonCode("X");
        src.setReason("r");
        src.setServerVersion("9.5.3");
        src.setClusterName("c");
        src.setStatus("yellow");
        src.setNodeCount(3);
        src.setIndexCount(4);
        src.setDocCount(5L);
        src.setStoreSizeBytes(6L);
        src.setStoreSizeHuman("6 B");
        src.setJvmHeapUsedBytes(7L);
        src.setJvmHeapMaxBytes(8L);
        src.setJvmHeapUsedPercent(9);
        src.setShardsActive(10);
        src.setShardsActivePrimary(11);
        src.setShardsRelocating(12);
        src.setShardsInitializing(13);
        src.setShardsUnassigned(14);
        src.setCollectedAt("2026-10-01 00:00:00");

        EsInfoVO vo = new EsInfoVO(src);
        assertEquals(src.isAvailable(), vo.isAvailable());
        assertEquals(src.getImplementation(), vo.getImplementation());
        assertEquals(src.getConfiguredType(), vo.getConfiguredType());
        assertEquals(src.isFallback(), vo.isFallback());
        assertEquals(src.getReasonCode(), vo.getReasonCode());
        assertEquals(src.getReason(), vo.getReason());
        assertEquals(src.getServerVersion(), vo.getServerVersion());
        assertEquals(src.getClusterName(), vo.getClusterName());
        assertEquals(src.getStatus(), vo.getStatus());
        assertEquals(src.getNodeCount(), vo.getNodeCount());
        assertEquals(src.getIndexCount(), vo.getIndexCount());
        assertEquals(src.getDocCount(), vo.getDocCount());
        assertEquals(src.getStoreSizeBytes(), vo.getStoreSizeBytes());
        assertEquals(src.getStoreSizeHuman(), vo.getStoreSizeHuman());
        assertEquals(src.getJvmHeapUsedBytes(), vo.getJvmHeapUsedBytes());
        assertEquals(src.getJvmHeapMaxBytes(), vo.getJvmHeapMaxBytes());
        assertEquals(src.getJvmHeapUsedPercent(), vo.getJvmHeapUsedPercent());
        assertEquals(src.getShardsActive(), vo.getShardsActive());
        assertEquals(src.getShardsActivePrimary(), vo.getShardsActivePrimary());
        assertEquals(src.getShardsRelocating(), vo.getShardsRelocating());
        assertEquals(src.getShardsInitializing(), vo.getShardsInitializing());
        assertEquals(src.getShardsUnassigned(), vo.getShardsUnassigned());
        assertEquals(src.getCollectedAt(), vo.getCollectedAt());
    }

    /** ⑪ 人类可读字节数 */
    @Test
    void humanBytesFormatting() {
        assertEquals("0 B", SearchHealthSnapshot.humanBytes(0));
        assertEquals("512 B", SearchHealthSnapshot.humanBytes(512));
        assertEquals("1.00 KB", SearchHealthSnapshot.humanBytes(1024));
        assertEquals("1.00 MB", SearchHealthSnapshot.humanBytes(1024 * 1024));
    }

    /** ⑫ 节点/索引明细在降级态是空列表而不是 null（前端安全） */
    @Test
    void degradedSnapshotHasEmptyLists() {
        EsInfoVO vo = new EsInfoService(providers(), properties(props("simple"))).collect();
        assertTrue(vo.getNodes().isEmpty());
        assertTrue(vo.getIndices().isEmpty());
    }

    /** ⑬ 未启用时 implementation 允许为空（本来就没有生效实现可言），但 reason 必须有 */
    @Test
    void notEnabledHasReasonButNoImplementation() {
        EsInfoVO vo = new EsInfoService(providers(), properties(null)).collect();
        assertEquals(null, vo.getImplementation());
        assertNotNull(vo.getReason());
        assertFalse(vo.isFallback());
    }

    /** ⑭ 快照 available=true 时不应带 reason（避免前端同时显示「正常」和「异常原因」） */
    @Test
    void healthySnapshotHasNoReason() {
        SearchHealthProvider provider = mock(SearchHealthProvider.class);
        when(provider.type()).thenReturn(SearchProviderType.ES_JAVA);
        when(provider.collect()).thenAnswer(invocation -> {
            SearchHealthSnapshot s = new SearchHealthSnapshot();
            s.setAvailable(true);
            s.setImplementation("es-java");
            return s;
        });
        EsInfoVO vo = new EsInfoService(providers(provider), properties(props("es-java"))).collect();
        assertTrue(vo.isAvailable());
        assertEquals(null, vo.getReason());
        assertEquals(null, vo.getReasonCode());
    }

    /** ⑮ ObjectProvider 本身抛异常也要被兜住（防御：Spring 容器异常不应变成 500） */
    @Test
    void objectProviderFailureDegrades() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SearchHealthProvider> broken = mock(ObjectProvider.class);
        when(broken.orderedStream()).thenThrow(new IllegalStateException("bean factory broken"));

        EsInfoVO vo = new EsInfoService(broken, properties(props("es-java"))).collect();
        assertFalse(vo.isAvailable());
        assertEquals(SearchUnavailableReason.COLLECT_FAILED.name(), vo.getReasonCode());
    }

    /** ⑯ 未匹配到配置类型时退化为第一个 Provider（行为确定，不静默丢弃） */
    @Test
    void fallsBackToFirstProviderWhenTypeMismatch() {
        SearchHealthProvider only = mock(SearchHealthProvider.class);
        when(only.type()).thenReturn(SearchProviderType.EASY_ES);
        when(only.collect()).thenReturn(new SearchHealthSnapshot());

        EsInfoVO vo = new EsInfoService(providers(only), properties(props("es-java"))).collect();
        org.mockito.Mockito.verify(only, org.mockito.Mockito.times(1)).collect();
        assertNotNull(vo);
        assertFalse(vo.isAvailable(), "拿到的快照默认 available=false");
    }

    /** ⑰ 采集返回的快照为 null（实现违规）时也要降级，不能 NPE */
    @Test
    void nullSnapshotDegradesGracefully() {
        SearchHealthProvider provider = mock(SearchHealthProvider.class);
        when(provider.type()).thenReturn(SearchProviderType.ES_JAVA);
        when(provider.collect()).thenReturn(null);

        EsInfoVO vo = new EsInfoService(providers(provider), properties(props("es-java"))).collect();
        assertNotNull(vo);
        assertFalse(vo.isAvailable());
    }

    /** ⑱ 配置值为空串时按 simple 处理（不被误判成回落） */
    @Test
    void blankTypeIsNotFallback() {
        EsInfoVO vo = new EsInfoService(providers(), properties(props("  "))).collect();
        assertFalse(vo.isFallback());
        assertEquals(SearchUnavailableReason.SIMPLE_IMPL.name(), vo.getReasonCode());
    }

    /** ⑲ 大小写与空格不敏感（SearchProviderType.of 的既有口径） */
    @Test
    void typeMatchIsCaseInsensitive() {
        SearchHealthProvider provider = mock(SearchHealthProvider.class);
        when(provider.type()).thenReturn(SearchProviderType.ES_JAVA);
        when(provider.collect()).thenAnswer(invocation -> {
            SearchHealthSnapshot s = new SearchHealthSnapshot();
            s.setAvailable(true);
            return s;
        });
        EsInfoVO vo = new EsInfoService(providers(provider), properties(props(" ES-Java "))).collect();
        assertTrue(vo.isAvailable());
    }

    /** ⑳ 快照必须带采集时间（前端展示「数据截至」） */
    @Test
    void snapshotCarriesCollectedAt() {
        SearchHealthProvider provider = mock(SearchHealthProvider.class);
        when(provider.type()).thenReturn(SearchProviderType.ES_JAVA);
        when(provider.collect()).thenAnswer(invocation -> {
            SearchHealthSnapshot s = new SearchHealthSnapshot();
            s.setAvailable(true);
            s.setCollectedAt("2026-10-01 12:00:00");
            return s;
        });
        EsInfoVO vo = new EsInfoService(providers(provider), properties(props("es-java"))).collect();
        assertEquals("2026-10-01 12:00:00", vo.getCollectedAt());
    }
}
