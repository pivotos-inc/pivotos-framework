package com.pivotos.starter.search.api;

import com.pivotos.starter.search.api.core.SearchIndexNameResolver;
import com.pivotos.starter.search.api.document.SearchRequest;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.fixture.PlainEntity;
import com.pivotos.starter.search.api.fixture.SearchTestEntity;
import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.query.SearchOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约层其余不变量：实现类型解析、索引名解析、分页偏移、异常语义。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class ContractTest {

    @Test
    void shouldParseProviderTypeCaseInsensitive() {
        assertEquals(SearchProviderType.SIMPLE, SearchProviderType.of("simple"));
        assertEquals(SearchProviderType.EASY_ES, SearchProviderType.of("EASY-ES"));
        assertEquals(SearchProviderType.ES_JAVA, SearchProviderType.of(" es-java "));
    }

    @Test
    void shouldReturnNullForUnknownTypeSoCallerDecidesFallback() {
        assertNull(SearchProviderType.of("milvus"));
        assertNull(SearchProviderType.of(""));
        assertNull(SearchProviderType.of(null));
    }

    @Test
    void shouldResolveIndexFromAnnotation() {
        assertEquals("search-test-entity", SearchIndexNameResolver.resolve(SearchTestEntity.class));
    }

    @Test
    void shouldResolveIndexFromClassNameWhenNoAnnotation() {
        assertEquals("plain-entity", SearchIndexNameResolver.resolve(PlainEntity.class));
    }

    @Test
    void shouldApplyAndSkipIndexPrefix() {
        assertEquals("dev_plain-entity", SearchIndexNameResolver.applyPrefix("plain-entity", "dev"));
        assertEquals("plain-entity", SearchIndexNameResolver.applyPrefix("plain-entity", ""));
        assertEquals("plain-entity", SearchIndexNameResolver.applyPrefix("plain-entity", null));
    }

    @Test
    void shouldRejectIllegalIndexName() {
        SearchException e = assertThrows(SearchException.class,
                () -> SearchIndexNameResolver.requireValid("Illegal_Index"));
        assertEquals(SearchErrorCode.INDEX_NAME_INVALID.getCode(), e.getCode());
    }

    @Test
    void shouldComputeOffset() {
        SearchRequest r = SearchRequest.of("idx", List.of(), List.of(), 3, 10);
        assertEquals(20, r.offset());
        // 非法页码不能算出负偏移
        assertEquals(0, SearchRequest.of("idx", List.of(), List.of(), -5, 10).offset());
    }

    @Test
    void shouldExposeFirstValueAndCollection() {
        SearchCriteria in = SearchCriteria.of("status", com.pivotos.starter.search.api.enums.SearchOp.IN,
                com.pivotos.starter.search.api.enums.SearchLogic.AND, List.of(1, 2));
        assertEquals(1, in.value());
        assertEquals(2, in.collection().size());
        assertThrows(UnsupportedOperationException.class, () -> in.getValues().add(3));
    }

    @Test
    void shouldBuildOrder() {
        assertTrue(SearchOrder.asc("title").isAsc());
        assertEquals(false, SearchOrder.desc("title").isAsc());
    }
}
