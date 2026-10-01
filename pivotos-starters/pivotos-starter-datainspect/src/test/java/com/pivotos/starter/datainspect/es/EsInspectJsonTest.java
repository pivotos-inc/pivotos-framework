package com.pivotos.starter.datainspect.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ES 原始 JSON 解析（纯函数）：跨版本形态必须靠单测钉死。
 *
 * <p>为什么必须测：{@code _cat/*} 的数值列有 {@code 35} / {@code "35"} / {@code "35.6"} / {@code "-"} /
 * {@code null} 五种形态，且 7.x 与 9.x 的系统内建索引数量不同——不排除就「两版本不可比」。
 */
class EsInspectJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void 索引清单解析_排除系统内建索引() throws Exception {
        String body = """
                [{"index":".geoip_databases","health":"green","status":"open","docs.count":"42",
                  "store.size":"40621638","pri":"1","rep":"0"},
                 {"index":"sys-oper-log","health":"yellow","status":"open","docs.count":"55",
                  "store.size":"71795","pri":"1","rep":"1"},
                 {"index":"ai-kb-chunk","health":"yellow","status":"open","docs.count":"1",
                  "store.size":"9143","pri":"1","rep":"1"}]
                """;
        List<EsInspectJson.IndexRow> rows = EsInspectJson.parseIndices(json(body));
        assertEquals(2, rows.size(), "系统内建索引（. 开头）必须排除，否则 7.x 与 9.x 不可比");
        assertEquals("ai-kb-chunk", rows.get(0).index(), "按索引名排序，保证两版本顺序稳定");
        assertEquals("sys-oper-log", rows.get(1).index());
        assertEquals(55, rows.get(1).docs());
        assertEquals(71795, rows.get(1).storeBytes());
        assertEquals("yellow", rows.get(1).health());
    }

    @Test
    void 数值列的五种形态统一兜底() throws Exception {
        String body = """
                [{"index":"a","docs.count":35,"store.size":100},
                 {"index":"b","docs.count":"35.6","store.size":"100"},
                 {"index":"c","docs.count":"-","store.size":null},
                 {"index":"d"}]
                """;
        List<EsInspectJson.IndexRow> rows = EsInspectJson.parseIndices(json(body));
        assertEquals(35, rows.get(0).docs(), "数字形态");
        assertEquals(35, rows.get(1).docs(), "字符串小数形态");
        assertEquals(-1, rows.get(2).docs(), "'-' 与 null 一律按未知 -1");
        assertEquals(-1, rows.get(2).storeBytes());
        assertEquals(-1, rows.get(3).docs(), "缺字段按未知 -1");
    }

    @Test
    void 非数组响应返回空清单() throws Exception {
        assertTrue(EsInspectJson.parseIndices(null).isEmpty());
        assertTrue(EsInspectJson.parseIndices(json("{}")).isEmpty());
    }

    @Test
    void 搜索响应解析_total与文档字段() throws Exception {
        String body = """
                {"took":3,"hits":{"total":{"value":55,"relation":"eq"},"max_score":1.0,
                  "hits":[{"_index":"sys-oper-log","_id":"abc","_score":1.0,
                           "_source":{"title":"登录","cost_ms":12,"ok":true}},
                          {"_index":"sys-oper-log","_id":"def","_score":1.0,
                           "_source":{"title":"导出","nested":{"a":1}}}]}}
                """;
        EsInspectJson.SearchPage page = EsInspectJson.parseSearch(json(body));
        assertEquals(55, page.total());
        assertEquals(2, page.docs().size());
        Map<String, Object> first = page.docs().get(0);
        assertEquals("abc", first.get("_id"));
        assertEquals("登录", first.get("title"));
        assertEquals(12, ((Number) first.get("cost_ms")).intValue());
        assertEquals(true, first.get("ok"));
        // 嵌套结构收敛成字符串，避免把表格撑爆
        assertEquals("{\"a\":1}", String.valueOf(page.docs().get(1).get("nested")).replace(" ", ""));
    }

    @Test
    void 搜索响应缺字段时降级为空结果() {
        assertTrue(EsInspectJson.parseSearch(null).docs().isEmpty());
        assertEquals(-1, EsInspectJson.parseSearch(null).total());
    }
}
