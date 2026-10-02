package com.pivotos.starter.search.esjava;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全文映射 JSON 的结构断言（S128）。
 * <p>存在理由：映射 JSON 是<b>手工字符串拼接</b>（低层 RestClient 只收字符串），
 * 括号写错时 ES 返回 400，而索引随后会被文档的<b>动态映射</b>兜住——
 * 表面上看「索引建出来了、也能搜」，实际字段类型由动态映射说了算，
 * 我们显式声明的那份 mapping 从未生效。本轮 dev 起服就是这么踩到的（真机 IT 没发现：
 * 那时索引恰好是空的，动态映射给的也是 text，结果一样）。这类错误只能靠结构断言钉死。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class EsJavaFullTextMappingTest {

    @Test
    void shouldBuildBalancedJsonWithPropertiesInsideMappings() {
        String json = EsJavaSearchProvider.buildFullTextMapping(List.of("content", "title"));

        assertEquals(count(json, '{'), count(json, '}'), "左右大括号必须配平");
        assertTrue(json.startsWith("{\"mappings\":{"), "mappings 必须是根节点的唯一键");
        assertTrue(json.endsWith("}}}"), "括号层级：根 + mappings + properties");
        int mappingsAt = json.indexOf("\"mappings\":");
        int templatesAt = json.indexOf("\"dynamic_templates\":");
        int propertiesAt = json.indexOf("\"properties\":");
        assertTrue(mappingsAt < templatesAt && templatesAt < propertiesAt,
                "properties 必须与 dynamic_templates 同级、都在 mappings 之内（写错时会被挤到根节点，ES 直接 400）");
    }

    @Test
    void shouldDeclareFieldsAsTextAndKeepKeywordDynamicTemplate() {
        String json = EsJavaSearchProvider.buildFullTextMapping(List.of("content"));

        assertTrue(json.contains("\"content\":{\"type\":\"text\"}"), "显式字段必须是 text（BM25 靠它）");
        assertTrue(json.contains("\"match_mapping_type\":\"string\""), "其余字符串仍走 keyword 动态模板");
        assertFalse(json.contains("analyzer"), "不指定 analyzer：避免「有没有插件」变成环境差异");
    }

    @Test
    void shouldBuildPropertiesOnlyForMappingUpdate() {
        assertEquals("{\"properties\":{\"content\":{\"type\":\"text\"}}}",
                EsJavaSearchProvider.buildProperties(List.of("content")));
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char ch : s.toCharArray()) {
            if (ch == c) {
                n++;
            }
        }
        return n;
    }
}
