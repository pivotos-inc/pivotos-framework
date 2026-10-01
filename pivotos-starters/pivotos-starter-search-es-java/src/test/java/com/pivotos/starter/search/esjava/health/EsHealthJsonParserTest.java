package com.pivotos.starter.search.esjava.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.search.api.health.SearchHealthSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ES 健康 JSON 解析（<b>7.17 与 9.x 双版本样本</b>）。
 * <p>样本取自两个真机的实际响应形态，钉死三件事：
 * <ol>
 *   <li>{@code _cat/nodes} 的 {@code node.role} 在 7.x 是短码、9.x 是长码——<b>原样带出不解析</b>；</li>
 *   <li>{@code _cat/indices} 的 {@code docs.count} 可能为 null（9.x）、{@code store.size} 可能是数字或字符串；</li>
 *   <li>系统内建索引（以 {@code .} 开头）不计入统计——否则 7.x 与 9.x 的索引数不可比。</li>
 * </ol>
 */
class EsHealthJsonParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- ES 9.5.3 样本 ----------
    private static final String HEALTH_9 = """
            {"cluster_name":"pivotos-es","status":"green","timed_out":false,
             "number_of_nodes":1,"number_of_data_nodes":1,
             "active_primary_shards":5,"active_shards":5,"relocating_shards":0,
             "initializing_shards":0,"unassigned_shards":0,"active_shards_percent_as_a_number":100.0}""";

    private static final String NODES_9 = """
            [{"ip":"175.24.176.176","heap.percent":"35","ram.percent":"92","cpu":"3",
              "load_1m":"1.23","node.role":"cdfhilmrstw","master":"*",
              "name":"es9-node-1","version":"9.5.3"}]""";

    private static final String INDICES_9 = """
            [{"index":".geoip_databases","health":"green","status":"open","docs.count":"40","store.size":"37892041","pri":"1","rep":"0"},
             {"index":"sys-oper-log","health":"green","status":"open","docs.count":"28","store.size":"104857","pri":"1","rep":"0"},
             {"index":"kb-document","health":"yellow","status":"open","docs.count":null,"store.size":"2048","pri":"1","rep":"1"}]""";

    private static final String JVM_9 = """
            {"cluster_name":"pivotos-es","nodes":{"node-A":{"name":"es9-node-1",
             "jvm":{"mem":{"heap_used_in_bytes":1073741824,"heap_max_in_bytes":4294967296,"heap_used_percent":25}}}}}""";

    // ---------- ES 7.17.28 样本 ----------
    private static final String HEALTH_7 = """
            {"cluster_name":"docker-cluster","status":"yellow","timed_out":false,
             "number_of_nodes":1,"number_of_data_nodes":1,
             "active_primary_shards":3,"active_shards":3,"relocating_shards":0,
             "initializing_shards":1,"unassigned_shards":2}""";

    private static final String NODES_7 = """
            [{"ip":"172.17.0.2","heap.percent":"12","ram.percent":"80","cpu":"1",
              "load_1m":"0.05","node.role":"di","master":"*","name":"es7-node","version":"7.17.28"}]""";

    private static final String INDICES_7 = """
            [{"index":".geoip_databases","health":"green","status":"open","docs.count":"40","store.size":"37892041","pri":"1","rep":"0"},
             {"index":"sys-oper-log","health":"green","status":"open","docs.count":"24","store.size":"51200","pri":"1","rep":"0"},
             {"index":"kb-document","health":"green","status":"open","docs.count":"1","store.size":"1024","pri":"1","rep":"0"}]""";

    private static final String JVM_7 = """
            {"cluster_name":"docker-cluster","nodes":{"node-B":{"name":"es7-node",
             "jvm":{"mem":{"heap_used_in_bytes":536870912,"heap_max_in_bytes":1073741824,"heap_used_percent":50}}}}}""";

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    void clusterHealthParsedForBothVersions() throws Exception {
        SearchHealthSnapshot s9 = new SearchHealthSnapshot();
        EsHealthJsonParser.applyClusterHealth(s9, json(HEALTH_9));
        assertEquals("pivotos-es", s9.getClusterName());
        assertEquals("green", s9.getStatus());
        assertEquals(1, s9.getNodeCount());
        assertEquals(5, s9.getShardsActive());
        assertEquals(5, s9.getShardsActivePrimary());
        assertEquals(0, s9.getShardsUnassigned());

        SearchHealthSnapshot s7 = new SearchHealthSnapshot();
        EsHealthJsonParser.applyClusterHealth(s7, json(HEALTH_7));
        assertEquals("yellow", s7.getStatus());
        assertEquals(1, s7.getShardsInitializing());
        assertEquals(2, s7.getShardsUnassigned());
    }

    /** 7.x 短码与 9.x 长码都原样带出（前端不解析角色码） */
    @Test
    void nodeRolesKeptVerbatimAcrossVersions() throws Exception {
        List<SearchHealthSnapshot.NodeInfo> n9 = EsHealthJsonParser.parseNodes(json(NODES_9));
        assertEquals(1, n9.size());
        assertEquals("cdfhilmrstw", n9.get(0).getRoles());
        assertTrue(n9.get(0).isMaster());
        assertEquals(35, n9.get(0).getHeapPercent());
        assertEquals(3, n9.get(0).getCpu());
        assertEquals(1.23D, n9.get(0).getLoad1m(), 1e-9);
        assertEquals("9.5.3", n9.get(0).getVersion());

        List<SearchHealthSnapshot.NodeInfo> n7 = EsHealthJsonParser.parseNodes(json(NODES_7));
        assertEquals(1, n7.size());
        assertEquals("di", n7.get(0).getRoles());
        assertTrue(n7.get(0).isMaster());
        assertEquals(12, n7.get(0).getHeapPercent());
        assertEquals(0.05D, n7.get(0).getLoad1m(), 1e-9);
    }

    /** 系统内建索引（. 开头）不计入统计——两个大版本内建索引数量不同，计入会让口径不可比 */
    @Test
    void hiddenIndicesExcluded() throws Exception {
        List<SearchHealthSnapshot.IndexInfo> i9 = EsHealthJsonParser.parseIndices(json(INDICES_9));
        assertEquals(2, i9.size(), ".geoip_databases 应被排除");
        assertEquals("sys-oper-log", i9.get(0).getIndex());
        assertEquals(28L, i9.get(0).getDocsCount());
        assertEquals(104857L, i9.get(0).getStoreSizeBytes());
        assertEquals("102.40 KB", i9.get(0).getStoreSizeHuman());

        List<SearchHealthSnapshot.IndexInfo> i7 = EsHealthJsonParser.parseIndices(json(INDICES_7));
        assertEquals(2, i7.size());
    }

    /** 9.x 上 docs.count 可能为 null → 按 0 处理，不能 NPE */
    @Test
    void nullDocsCountTreatedAsZero() throws Exception {
        List<SearchHealthSnapshot.IndexInfo> i9 = EsHealthJsonParser.parseIndices(json(INDICES_9));
        assertEquals(0L, i9.get(1).getDocsCount(), "kb-document 的 docs.count=null 应落成 0");
    }

    @Test
    void indexTotalsAggregated() throws Exception {
        SearchHealthSnapshot s = new SearchHealthSnapshot();
        EsHealthJsonParser.applyIndexTotals(s, EsHealthJsonParser.parseIndices(json(INDICES_9)));
        assertEquals(2, s.getIndexCount());
        assertEquals(28L, s.getDocCount());
        assertEquals(104857L + 2048L, s.getStoreSizeBytes());
        assertEquals("104.40 KB", s.getStoreSizeHuman());

        EsHealthJsonParser.applyIndexTotals(s, null);
        assertEquals(0, s.getIndexCount());
        assertEquals(0L, s.getDocCount());
    }

    @Test
    void jvmHeapParsedForBothVersions() throws Exception {
        SearchHealthSnapshot s9 = new SearchHealthSnapshot();
        EsHealthJsonParser.applyJvmStats(s9, json(JVM_9));
        assertEquals(1073741824L, s9.getJvmHeapUsedBytes());
        assertEquals(4294967296L, s9.getJvmHeapMaxBytes());
        assertEquals(25, s9.getJvmHeapUsedPercent());

        SearchHealthSnapshot s7 = new SearchHealthSnapshot();
        EsHealthJsonParser.applyJvmStats(s7, json(JVM_7));
        assertEquals(536870912L, s7.getJvmHeapUsedBytes());
        assertEquals(50, s7.getJvmHeapUsedPercent());
    }

    /** 多节点：堆求和、百分比取最大 */
    @Test
    void jvmHeapAggregatesAcrossNodes() throws Exception {
        String raw = """
                {"nodes":{"a":{"jvm":{"mem":{"heap_used_in_bytes":100,"heap_max_in_bytes":200,"heap_used_percent":10}}},
                          "b":{"jvm":{"mem":{"heap_used_in_bytes":300,"heap_max_in_bytes":400,"heap_used_percent":80}}}}}""";
        SearchHealthSnapshot s = new SearchHealthSnapshot();
        EsHealthJsonParser.applyJvmStats(s, json(raw));
        assertEquals(400L, s.getJvmHeapUsedBytes());
        assertEquals(600L, s.getJvmHeapMaxBytes());
        assertEquals(80, s.getJvmHeapUsedPercent());
    }

    /** 节点数优先取 _cat/nodes 列表长度（比 _cluster/health 的 number_of_nodes 更贴近明细展示） */
    @Test
    void nodeCountOverriddenByCatNodes() throws Exception {
        SearchHealthSnapshot s = new SearchHealthSnapshot();
        EsHealthJsonParser.applyClusterHealth(s, json(HEALTH_9));
        s.setNodes(EsHealthJsonParser.parseNodes(json(NODES_9)));
        if (!s.getNodes().isEmpty()) {
            s.setNodeCount(s.getNodes().size());
        }
        assertEquals(1, s.getNodeCount());
    }

    /** 空/异常响应一律返回空列表，不抛异常（监控是只读旁路） */
    @Test
    void malformedInputNeverThrows() {
        assertTrue(EsHealthJsonParser.parseNodes(null).isEmpty());
        assertTrue(EsHealthJsonParser.parseIndices(null).isEmpty());
        SearchHealthSnapshot s = new SearchHealthSnapshot();
        EsHealthJsonParser.applyClusterHealth(s, null);
        EsHealthJsonParser.applyJvmStats(s, null);
        assertNull(s.getStatus());
        assertEquals(0L, s.getJvmHeapUsedBytes());
    }

    /** 缺字段（如某平台没有 load_1m）时取默认值而不是崩 */
    @Test
    void missingFieldsDefaultToZero() throws Exception {
        String raw = "[{\"name\":\"n1\",\"master\":\"-\"}]";
        List<SearchHealthSnapshot.NodeInfo> nodes = EsHealthJsonParser.parseNodes(json(raw));
        assertEquals(1, nodes.size());
        assertFalse(nodes.get(0).isMaster());
        assertEquals(0, nodes.get(0).getHeapPercent());
        assertEquals(0D, nodes.get(0).getLoad1m(), 1e-9);
    }

    /** cat 的数值列既可能是数字也可能是字符串，两种形态都要能读 */
    @Test
    void numericAndTextualValuesBothAccepted() throws Exception {
        String raw = "[{\"index\":\"a\",\"docs.count\":12,\"store.size\":2048,\"pri\":\"1\",\"rep\":\"0\"},"
                + "{\"index\":\"b\",\"docs.count\":\"3\",\"store.size\":\"1024\",\"pri\":1,\"rep\":0}]";
        List<SearchHealthSnapshot.IndexInfo> indices = EsHealthJsonParser.parseIndices(json(raw));
        assertEquals(2, indices.size());
        assertEquals(12L, indices.get(0).getDocsCount());
        assertEquals(2048L, indices.get(0).getStoreSizeBytes());
        assertEquals(1, indices.get(0).getPri());
        assertEquals(3L, indices.get(1).getDocsCount());
        assertEquals(1024L, indices.get(1).getStoreSizeBytes());
    }

    /** 带小数的字符串列（如 cpu="2.0"）按四舍五入取整 */
    @Test
    void decimalStringsRounded() throws Exception {
        String raw = "[{\"name\":\"n\",\"cpu\":\"2.0\",\"heap.percent\":\"35.6\"}]";
        List<SearchHealthSnapshot.NodeInfo> nodes = EsHealthJsonParser.parseNodes(json(raw));
        assertEquals(2, nodes.get(0).getCpu());
        assertEquals(36, nodes.get(0).getHeapPercent());
    }

    /** 非数字列（如 heap.percent="-"）按 0 处理，不抛 NumberFormatException */
    @Test
    void nonNumericStringsTreatedAsZero() throws Exception {
        String raw = "[{\"name\":\"n\",\"heap.percent\":\"-\",\"load_1m\":\"\"}]";
        List<SearchHealthSnapshot.NodeInfo> nodes = EsHealthJsonParser.parseNodes(json(raw));
        assertEquals(0, nodes.get(0).getHeapPercent());
        assertEquals(0D, nodes.get(0).getLoad1m(), 1e-9);
    }

    /** 人类可读字节数 */
    @Test
    void humanBytes() {
        assertEquals("0 B", SearchHealthSnapshot.humanBytes(0));
        assertEquals("1.00 KB", SearchHealthSnapshot.humanBytes(1024));
        assertEquals("1.00 MB", SearchHealthSnapshot.humanBytes(1024 * 1024));
        assertEquals("1.00 GB", SearchHealthSnapshot.humanBytes(1024L * 1024 * 1024));
    }

    /** 降级快照工厂：available=false + 原因码 + 文案带细节 */
    @Test
    void unavailableFactory() {
        SearchHealthSnapshot s = SearchHealthSnapshot.unavailable(
                "simple", "es-java", true,
                com.pivotos.starter.search.api.health.SearchUnavailableReason.FALLBACK, "启动期自检未通过");
        assertFalse(s.isAvailable());
        assertTrue(s.isFallback());
        assertEquals("FALLBACK", s.getReasonCode());
        assertTrue(s.getReason().contains("启动期自检未通过"));
        assertTrue(s.getNodes().isEmpty());
        assertTrue(s.getIndices().isEmpty());
    }
}
