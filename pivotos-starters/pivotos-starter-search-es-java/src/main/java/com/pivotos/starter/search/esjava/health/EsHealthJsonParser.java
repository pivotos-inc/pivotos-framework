package com.pivotos.starter.search.esjava.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.pivotos.starter.search.api.health.SearchHealthSnapshot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ES 健康指标 JSON 解析（读原始 JSON，不碰类型化 API）。
 *
 * <h3>为什么要自己解析</h3>
 * <ol>
 *   <li>采集端点 {@code _cluster/health} / {@code _cat/nodes} / {@code _cat/indices} /
 *       {@code _nodes/stats} 在 7.17 与 8.x/9.x 上<b>字段名大体一致但细节有差</b>
 *       （如 {@code node.role} 字面值、{@code docs.count} 可能为 null、
 *       9.x 的 cat 列比 7.x 多），读原始 JSON 逐字段容错最稳；</li>
 *   <li>官方客户端的 cat 类型定义在跨版本时可能收紧（未知列/未知值解析失败），
 *       而监控是只读旁路——解析失败应该退化成「该指标缺失」，不是把整个页面打挂。</li>
 * </ol>
 *
 * <h3>容错口径</h3>
 * 任何字段缺失 / 非数字一律按「该指标取默认值」处理（0 或空串），<b>不抛异常</b>；
 * 只有「整个响应拿不到」才由调用方判为不可用。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EsHealthJsonParser {

    private EsHealthJsonParser() {
    }

    /** {@code GET /_cluster/health} */
    public static void applyClusterHealth(SearchHealthSnapshot snapshot, JsonNode root) {
        if (snapshot == null || root == null) {
            return;
        }
        snapshot.setClusterName(text(root, "cluster_name"));
        snapshot.setStatus(text(root, "status"));
        snapshot.setNodeCount(asInt(root.get("number_of_nodes")));
        snapshot.setShardsActive(asInt(root.get("active_shards")));
        snapshot.setShardsActivePrimary(asInt(root.get("active_primary_shards")));
        snapshot.setShardsRelocating(asInt(root.get("relocating_shards")));
        snapshot.setShardsInitializing(asInt(root.get("initializing_shards")));
        snapshot.setShardsUnassigned(asInt(root.get("unassigned_shards")));
    }

    /**
     * {@code GET /_cat/nodes?format=json} → 节点明细。
     * <p>节点数优先取本列表长度（{@code _cluster/health} 的 number_of_nodes 在部分版本口径不同）。
     */
    public static List<SearchHealthSnapshot.NodeInfo> parseNodes(JsonNode array) {
        List<SearchHealthSnapshot.NodeInfo> nodes = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return nodes;
        }
        for (JsonNode item : array) {
            SearchHealthSnapshot.NodeInfo node = new SearchHealthSnapshot.NodeInfo();
            node.setName(text(item, "name"));
            node.setIp(text(item, "ip"));
            node.setVersion(text(item, "version"));
            // 7.x 短码（di/mdi）与 8.x/9.x 长码（cdfhilmrstw）都原样带出，前端不解析
            node.setRoles(text(item, "node.role"));
            node.setMaster("*".equals(text(item, "master")));
            node.setHeapPercent(asInt(item.get("heap.percent")));
            node.setRamPercent(asInt(item.get("ram.percent")));
            node.setCpu(asInt(item.get("cpu")));
            node.setLoad1m(asDouble(item.get("load_1m")));
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * {@code GET /_cat/indices?format=json&bytes=b} → 索引明细。
     * <p><b>系统内建索引（以 {@code .} 开头，如 {@code .geoip_databases}、
     * {@code .security-7}）不计入统计</b>——它们不是业务数据，计入会让「索引数 / 文档总数」
     * 在 7.x 与 9.x 上不可比（9.x 内建索引明显更多）。
     */
    public static List<SearchHealthSnapshot.IndexInfo> parseIndices(JsonNode array) {
        List<SearchHealthSnapshot.IndexInfo> indices = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return indices;
        }
        for (JsonNode item : array) {
            String name = text(item, "index");
            if (name == null || name.startsWith(".")) {
                continue;
            }
            SearchHealthSnapshot.IndexInfo index = new SearchHealthSnapshot.IndexInfo();
            index.setIndex(name);
            index.setHealth(text(item, "health"));
            index.setStatus(text(item, "status"));
            // 9.x 上 docs.count 可能为 null（如正在初始化/关闭态索引），按 0 处理
            index.setDocsCount(asLong(item.get("docs.count")));
            index.setPri(asInt(item.get("pri")));
            index.setRep(asInt(item.get("rep")));
            long storeSize = asLong(item.get("store.size"));
            index.setStoreSizeBytes(storeSize);
            index.setStoreSizeHuman(SearchHealthSnapshot.humanBytes(storeSize));
            indices.add(index);
        }
        return indices;
    }

    /** 按索引明细汇总「索引数 / 文档总数 / 存储大小」 */
    public static void applyIndexTotals(SearchHealthSnapshot snapshot, List<SearchHealthSnapshot.IndexInfo> indices) {
        if (snapshot == null) {
            return;
        }
        long docs = 0;
        long bytes = 0;
        if (indices != null) {
            for (SearchHealthSnapshot.IndexInfo index : indices) {
                docs += index.getDocsCount();
                bytes += index.getStoreSizeBytes();
            }
            snapshot.setIndexCount(indices.size());
        } else {
            snapshot.setIndexCount(0);
        }
        snapshot.setDocCount(docs);
        snapshot.setStoreSizeBytes(bytes);
        snapshot.setStoreSizeHuman(SearchHealthSnapshot.humanBytes(bytes));
    }

    /**
     * {@code GET /_nodes/stats/jvm} → JVM 堆。
     * <p>多节点时堆用量/上限<b>求和</b>（看总量），百分比取<b>最大值</b>（看最紧张的节点）。
     */
    public static void applyJvmStats(SearchHealthSnapshot snapshot, JsonNode root) {
        if (snapshot == null || root == null) {
            return;
        }
        JsonNode nodes = root.get("nodes");
        if (nodes == null || !nodes.isObject()) {
            return;
        }
        long used = 0;
        long max = 0;
        int maxPercent = 0;
        Iterator<Map.Entry<String, JsonNode>> it = nodes.fields();
        while (it.hasNext()) {
            JsonNode node = it.next().getValue();
            JsonNode mem = node.path("jvm").path("mem");
            used += asLong(mem.get("heap_used_in_bytes"));
            max += asLong(mem.get("heap_max_in_bytes"));
            maxPercent = Math.max(maxPercent, asInt(mem.get("heap_used_percent")));
        }
        snapshot.setJvmHeapUsedBytes(used);
        snapshot.setJvmHeapMaxBytes(max);
        snapshot.setJvmHeapUsedPercent(maxPercent);
    }

    // ==================== 取值工具（全部空安全） ====================

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static long asLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return 0L;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        return parseLong(value.asText());
    }

    private static int asInt(JsonNode value) {
        if (value == null || value.isNull()) {
            return 0;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        // cat 接口可能给 "35" 也可能给 "35.0"
        return (int) Math.round(parseDouble(value.asText()));
    }

    private static double asDouble(JsonNode value) {
        if (value == null || value.isNull()) {
            return 0D;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        return parseDouble(value.asText());
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0D;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 0D;
        }
    }
}
