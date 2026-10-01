package com.pivotos.starter.datainspect.es;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 监控的 JSON 解析（纯函数、零 IO，便于单测钉死跨版本形态）。
 *
 * <p>为什么不用类型化客户端：ES 7.17 / 8.x / 9.x 的 {@code _cat/*} 与 {@code _search} 在字段形态上
 * 有差异（数值列可能是 {@code "35"} / {@code "35.6"} / {@code "-"} / {@code null}），
 * 照 S127 的既有套路——<b>读原始 JSON + 统一 parseDouble 兜底</b>，一套代码吃三个版本。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public final class EsInspectJson {

    private EsInspectJson() {
    }

    /** {@code _cat/indices?format=json} 的一行 */
    public static final class IndexRow {
        private final String index;
        private final String health;
        private final String status;
        private final long docs;
        private final long storeBytes;
        private final String pri;
        private final String rep;

        IndexRow(String index, String health, String status, long docs, long storeBytes, String pri, String rep) {
            this.index = index;
            this.health = health;
            this.status = status;
            this.docs = docs;
            this.storeBytes = storeBytes;
            this.pri = pri;
            this.rep = rep;
        }

        public String index() {
            return index;
        }

        public String health() {
            return health;
        }

        public String status() {
            return status;
        }

        public long docs() {
            return docs;
        }

        public long storeBytes() {
            return storeBytes;
        }

        public String pri() {
            return pri;
        }

        public String rep() {
            return rep;
        }
    }

    /** {@code _search} 的解析结果 */
    public static final class SearchPage {
        private final long total;
        private final List<Map<String, Object>> docs;

        SearchPage(long total, List<Map<String, Object>> docs) {
            this.total = total;
            this.docs = docs;
        }

        public long total() {
            return total;
        }

        public List<Map<String, Object>> docs() {
            return docs;
        }
    }

    /**
     * 解析 {@code _cat/indices?format=json&bytes=b} 响应。
     *
     * <p>系统内建索引（{@code .} 开头，如 {@code .geoip_databases}）一律排除——
     * 否则 7.x 与 9.x 的内建索引数量不同，「同一份断言在两版本上都可比」就不成立（S127-B 教训）。
     */
    public static List<IndexRow> parseIndices(JsonNode root) {
        List<IndexRow> rows = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return rows;
        }
        for (JsonNode node : root) {
            String index = text(node, "index");
            if (index == null || index.isBlank() || index.startsWith(".")) {
                continue;
            }
            rows.add(new IndexRow(
                    index,
                    text(node, "health"),
                    text(node, "status"),
                    num(node, "docs.count", -1),
                    num(node, "store.size", -1),
                    text(node, "pri"),
                    text(node, "rep")));
        }
        rows.sort((a, b) -> a.index().compareTo(b.index()));
        return rows;
    }

    /**
     * 解析 {@code _search} 响应：total 取 {@code hits.total.value}（依赖请求里注入的
     * {@code track_total_hits:true}，否则 ES 默认只给到 10000 的上界）。
     */
    public static SearchPage parseSearch(JsonNode root) {
        if (root == null) {
            return new SearchPage(-1, List.of());
        }
        JsonNode hits = root.get("hits");
        if (hits == null) {
            return new SearchPage(-1, List.of());
        }
        long total = -1;
        JsonNode totalNode = hits.get("total");
        if (totalNode != null && totalNode.get("value") != null) {
            total = (long) num(totalNode, "value", -1);
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        JsonNode array = hits.get("hits");
        if (array != null && array.isArray()) {
            for (JsonNode hit : array) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("_id", text(hit, "_id"));
                JsonNode source = hit.get("_source");
                if (source != null && source.isObject()) {
                    source.fields().forEachRemaining(entry ->
                            doc.put(entry.getKey(), scalar(entry.getValue())));
                }
                docs.add(doc);
            }
        }
        return new SearchPage(total, docs);
    }

    /** 只取标量：对象/数组交给调用方截断成字符串，避免嵌套结构把表格撑爆 */
    private static Object scalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        return node.toString();
    }

    /**
     * {@code _cat/*} 数值列的五种形态统一兜底：{@code 35} / {@code "35"} / {@code "35.6"} / {@code "-"} / {@code null}。
     */
    private static long num(JsonNode node, String field, long defaultValue) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        String raw = value.asText();
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return defaultValue;
        }
        try {
            return (long) Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
