package com.pivotos.starter.search.simple;

import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchRequest;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.query.SearchOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * simple 内存实现：这是「无 ES 环境可起服、可跑 IT」的兜底能力，必须真能检出，
 * 不能退化成空实现。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class SimpleSearchProviderTest {

    private static final String INDEX = "oper-log-doc";

    private SimpleSearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SimpleSearchProvider();
        provider.createIndexIfAbsent(INDEX);
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> source = new HashMap<>();
            source.put("id", (long) i);
            source.put("title", "操作" + i);
            source.put("status", i % 2);
            provider.index(SearchDocument.of(INDEX, String.valueOf(i), source));
        }
    }

    @Test
    void shouldExposeSimpleType() {
        assertEquals(SearchProviderType.SIMPLE, provider.type());
    }

    @Test
    void shouldSearchAllWithoutCriteria() {
        SearchResult result = provider.search(SearchRequest.of(INDEX, List.of(), List.of(), 1, 10));
        assertEquals(5, result.getTotal());
        assertEquals(5, result.getHits().size());
    }

    @Test
    void shouldFilterByCriteria() {
        // status = i % 2，i=1..5 → 1,0,1,0,1，故 status=1 共 3 条
        SearchResult result = provider.search(SearchRequest.of(INDEX,
                List.of(SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1)),
                List.of(), 1, 10));
        assertEquals(3, result.getTotal());
    }

    @Test
    void shouldPaginate() {
        SearchResult page1 = provider.search(SearchRequest.of(INDEX, List.of(), List.of(), 1, 2));
        SearchResult page2 = provider.search(SearchRequest.of(INDEX, List.of(), List.of(), 2, 2));
        assertEquals(5, page1.getTotal());
        assertEquals(2, page1.getHits().size());
        assertEquals(2, page2.getHits().size());
        assertEquals(1, page3FirstId(page1));
        assertEquals(3, page3FirstId(page2));
    }

    private long page3FirstId(SearchResult result) {
        return ((Number) result.getHits().get(0).getSource().get("id")).longValue();
    }

    @Test
    void shouldSortByField() {
        SearchResult asc = provider.search(SearchRequest.of(INDEX, List.of(),
                List.of(SearchOrder.asc("id")), 1, 10));
        SearchResult desc = provider.search(SearchRequest.of(INDEX, List.of(),
                List.of(SearchOrder.desc("id")), 1, 10));
        assertEquals(1L, ((Number) asc.getHits().get(0).getSource().get("id")).longValue());
        assertEquals(5L, ((Number) desc.getHits().get(0).getSource().get("id")).longValue());
    }

    @Test
    void shouldCountIndependentlyOfPaging() {
        long total = provider.count(SearchRequest.of(INDEX, List.of(), List.of(), 1, 2));
        assertEquals(5, total);
    }

    @Test
    void shouldOverwriteOnSameIdAndDelete() {
        Map<String, Object> source = new HashMap<>();
        source.put("id", 1L);
        source.put("title", "覆盖后");
        provider.index(SearchDocument.of(INDEX, "1", source));
        assertEquals(5, provider.count(SearchRequest.of(INDEX, List.of(), List.of(), 1, 10)));

        provider.delete(INDEX, "1");
        assertEquals(4, provider.count(SearchRequest.of(INDEX, List.of(), List.of(), 1, 10)));
    }

    @Test
    void shouldSupportBatchIndex() {
        provider.clear();
        provider.createIndexIfAbsent(INDEX);
        provider.indexBatch(List.of(
                SearchDocument.of(INDEX, "a", Map.of("id", 1)),
                SearchDocument.of(INDEX, "b", Map.of("id", 2))));
        assertEquals(2, provider.count(SearchRequest.of(INDEX, List.of(), List.of(), 1, 10)));
    }

    @Test
    void shouldReturnEmptyForUnknownIndex() {
        assertTrue(provider.search(SearchRequest.of("not-exist", List.of(), List.of(), 1, 10))
                .getHits().isEmpty());
        assertFalse(provider.existsIndex("not-exist"));
        assertTrue(provider.existsIndex(INDEX));
    }

    @Test
    void shouldRejectIncompleteDocument() {
        assertThrows(IllegalArgumentException.class,
                () -> provider.index(SearchDocument.of(null, "1", Map.of())));
    }

    @Test
    void shouldNotLeakInternalMap() {
        // copySource 必须是副本：改外部 Map 不能污染索引内容
        Map<String, Object> source = new HashMap<>();
        source.put("id", 99L);
        provider.index(SearchDocument.of(INDEX, "99", source));
        source.put("id", -1L);
        SearchResult result = provider.search(SearchRequest.of(INDEX,
                List.of(SearchCriteria.of("id", SearchOp.EQ, SearchLogic.AND, 99L)), List.of(), 1, 10));
        assertEquals(1, result.getTotal());
    }
}
