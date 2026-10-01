package com.pivotos.starter.search.api.score;

import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.query.LambdaSearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打分检索请求的归一化与校验（S128）：默认值必须「配了能跑、不配也能跑」，
 * 且窗口不得小于 topK（否则「重排后截断」会退化成「先截断再重排」，召回被前端吃掉）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class ScoredSearchRequestTest {

    @Test
    void shouldApplyDefaultsOnMinimalConstruction() {
        ScoredSearchRequest request = ScoredSearchRequest.of("kb-chunk", "报销流程");

        assertEquals("kb-chunk", request.getIndexName());
        assertEquals("报销流程", request.getKeyword());
        assertEquals(ScoredSearchRequest.DEFAULT_TOP_K, request.getTopK());
        assertEquals(ScoredSearchRequest.DEFAULT_CANDIDATE_WINDOW, request.getCandidateWindow());
        assertEquals(0D, request.getMinScore(), 0D);
        assertFalse(request.hasFields());
        assertTrue(request.hasKeyword());
    }

    @Test
    void shouldNormalizeKeywordAndWindow() {
        ScoredSearchRequest request = ScoredSearchRequest.of(" idx ", "  报销流程  ", List.of(" content "),
                null, 5, 0.1D, 3);

        assertEquals("idx", request.getIndexName(), "索引名去空白");
        assertEquals("报销流程", request.getKeyword(), "关键词去空白");
        assertEquals(List.of(" content "), request.getFields(), "字段名原样保留（大小写敏感，交由实现校验）");
        assertEquals(5, request.getTopK());
        assertEquals(5, request.getCandidateWindow(), "窗口不得小于 topK");
        assertTrue(request.hasFields());
    }

    @Test
    void shouldClampToMaxPageSize() {
        ScoredSearchRequest request = ScoredSearchRequest.of("idx", "q", List.of(), List.of(),
                LambdaSearchQuery.MAX_PAGE_SIZE + 10, 0D, LambdaSearchQuery.MAX_PAGE_SIZE + 10);

        assertEquals(LambdaSearchQuery.MAX_PAGE_SIZE, request.getTopK());
        assertEquals(LambdaSearchQuery.MAX_PAGE_SIZE, request.getCandidateWindow());
    }

    @Test
    void shouldFallbackToDefaultTopKWhenNonPositive() {
        ScoredSearchRequest request = ScoredSearchRequest.of("idx", "q", List.of(), List.of(), 0, 0D, 0);
        assertEquals(ScoredSearchRequest.DEFAULT_TOP_K, request.getTopK());
        assertEquals(ScoredSearchRequest.DEFAULT_CANDIDATE_WINDOW, request.getCandidateWindow());
    }

    @Test
    void shouldRequireIndexName() {
        assertThrows(SearchException.class, () -> ScoredSearchRequest.of(null, "q"));
        assertThrows(SearchException.class, () -> ScoredSearchRequest.of("  ", "q"));
    }

    @Test
    void shouldTreatNullKeywordAsNoKeyword() {
        ScoredSearchRequest request = ScoredSearchRequest.of("idx", null);
        assertEquals("", request.getKeyword());
        assertFalse(request.hasKeyword());
    }
}
