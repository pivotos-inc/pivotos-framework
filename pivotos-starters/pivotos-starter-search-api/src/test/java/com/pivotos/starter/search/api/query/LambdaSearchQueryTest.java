package com.pivotos.starter.search.api.query;

import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.fixture.SearchTestEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lambda 门面 → 条件树：断言「糖」背后产物的确定性。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class LambdaSearchQueryTest {

    @Test
    void shouldBuildConditionTree() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 12, 31, 23, 59);

        LambdaSearchQuery<SearchTestEntity> q = LambdaSearchQuery.of(SearchTestEntity.class)
                .eq(SearchTestEntity::getStatus, 0)
                .like(SearchTestEntity::getTitle, "登录")
                .between(SearchTestEntity::getOperTime, start, end)
                .orderByDesc(SearchTestEntity::getOperTime)
                .page(2, 10);

        assertEquals(3, q.getCriteria().size());
        assertEquals("status", q.getCriteria().get(0).getField());
        assertEquals(SearchOp.EQ, q.getCriteria().get(0).getOp());
        assertEquals("title", q.getCriteria().get(1).getField());
        assertEquals(SearchOp.LIKE, q.getCriteria().get(1).getOp());
        assertEquals("operTime", q.getCriteria().get(2).getField());
        assertEquals(SearchOp.BETWEEN, q.getCriteria().get(2).getOp());
        assertEquals(List.of(start, end), q.getCriteria().get(2).getValues());

        assertEquals(1, q.getOrders().size());
        assertEquals("operTime", q.getOrders().get(0).getField());
        assertEquals(false, q.getOrders().get(0).isAsc());

        assertEquals(2, q.getPageNum());
        assertEquals(10, q.getPageSize());
    }

    @Test
    void shouldDefaultToFirstPageAndTwentyRows() {
        LambdaSearchQuery<SearchTestEntity> q = LambdaSearchQuery.of(SearchTestEntity.class);
        assertEquals(1, q.getPageNum());
        assertEquals(20, q.getPageSize());
        assertTrue(q.getCriteria().isEmpty());
    }

    @Test
    void shouldForceAndForFirstConditionEvenWhenDeclaredOr() {
        // 首条件无前驱可连接，无论声明什么都必须是 AND
        LambdaSearchQuery<SearchTestEntity> q = LambdaSearchQuery.of(SearchTestEntity.class)
                .orEq(SearchTestEntity::getStatus, 1)
                .orEq(SearchTestEntity::getStatus, 2);

        assertEquals(SearchLogic.AND, q.getCriteria().get(0).getLogic());
        assertEquals(SearchLogic.OR, q.getCriteria().get(1).getLogic());
    }

    @Test
    void shouldFlattenCollectionForInCondition() {
        LambdaSearchQuery<SearchTestEntity> q = LambdaSearchQuery.of(SearchTestEntity.class)
                .in(SearchTestEntity::getStatus, List.of(1, 2, 3));

        assertEquals(SearchOp.IN, q.getCriteria().get(0).getOp());
        assertEquals(List.of(1, 2, 3), q.getCriteria().get(0).getValues());
    }

    @Test
    void shouldRejectIllegalPageParams() {
        assertThrows(SearchException.class,
                () -> LambdaSearchQuery.of(SearchTestEntity.class).page(0, 10));
        assertThrows(SearchException.class,
                () -> LambdaSearchQuery.of(SearchTestEntity.class).page(1, 0));
        assertThrows(SearchException.class,
                () -> LambdaSearchQuery.of(SearchTestEntity.class).page(1, LambdaSearchQuery.MAX_PAGE_SIZE + 1));
    }

    @Test
    void shouldRejectInWithEmptyCollection() {
        SearchException e = assertThrows(SearchException.class, () -> LambdaSearchQuery
                .of(SearchTestEntity.class)
                .in(SearchTestEntity::getStatus, List.of()));
        assertEquals(SearchErrorCode.QUERY_PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    void shouldRejectConditionWithoutValue() {
        assertThrows(SearchException.class,
                () -> LambdaSearchQuery.of(SearchTestEntity.class).eq(SearchTestEntity::getStatus, null));
    }

    @Test
    void shouldResolveNullOperatorsWithoutValues() {
        LambdaSearchQuery<SearchTestEntity> q = LambdaSearchQuery.of(SearchTestEntity.class)
                .isNull(SearchTestEntity::getTitle)
                .isNotNull(SearchTestEntity::getStatus);

        assertTrue(q.getCriteria().get(0).getValues().isEmpty());
        assertEquals(SearchOp.IS_NULL, q.getCriteria().get(0).getOp());
        assertEquals(SearchOp.IS_NOT_NULL, q.getCriteria().get(1).getOp());
    }

    @Test
    void shouldRejectNullEntityType() {
        assertThrows(SearchException.class, () -> LambdaSearchQuery.of(null));
    }

    @Test
    void shouldSupportLimitAndExplicitIndex() {
        LambdaSearchQuery<SearchTestEntity> q = LambdaSearchQuery.of(SearchTestEntity.class)
                .limit(5)
                .index("custom-index");

        assertEquals(1, q.getPageNum());
        assertEquals(5, q.getPageSize());
        assertEquals("custom-index", q.getIndexName());
    }
}
