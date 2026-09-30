package com.pivotos.starter.search.esjava.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.query.SearchCriteria;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件树 → es-java Query 翻译（离线断言产出的 DSL JSON）。
 * <p>本机无 ES 服务端，因此能验证的边界是「请求构建正确」；
 * 真实网络执行链路需要在有 ES 的环境补验（已写入收口报告遗留项）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class EsJavaQueryBuilderTest {

    private static String json(Query query) {
        return query.toString();
    }

    @Test
    void shouldBuildMatchAllWhenNoCriteria() {
        // es-java 的 toString 带类型前缀（Query: {...}）
        assertTrue(json(EsJavaQueryBuilder.build(List.of())).endsWith("{\"match_all\":{}}"));
        assertTrue(json(EsJavaQueryBuilder.build(null)).endsWith("{\"match_all\":{}}"));
    }

    @Test
    void shouldBuildTermQuery() {
        Query q = EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1)));
        String json = json(q);
        assertTrue(json.contains("\"term\""), json);
        assertTrue(json.contains("\"status\""), json);
    }

    @Test
    void shouldBuildMustNotForNotEqual() {
        Query q = EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("status", SearchOp.NE, SearchLogic.AND, 1)));
        String json = json(q);
        assertTrue(json.contains("must_not"), json);
        assertTrue(json.contains("\"term\""), json);
    }

    @Test
    void shouldBuildRangeQuery() {
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("age", SearchOp.GT, SearchLogic.AND, 18)))).contains("\"gt\""));
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("age", SearchOp.GE, SearchLogic.AND, 18)))).contains("\"gte\""));
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("age", SearchOp.LT, SearchLogic.AND, 18)))).contains("\"lt\""));
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("age", SearchOp.LE, SearchLogic.AND, 18)))).contains("\"lte\""));
    }

    @Test
    void shouldBuildBetweenAsClosedRange() {
        String json = json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("age", SearchOp.BETWEEN, SearchLogic.AND, 18, 60))));
        assertTrue(json.contains("\"gte\""), json);
        assertTrue(json.contains("\"lte\""), json);
    }

    @Test
    void shouldBuildWildcardForLikeVariants() {
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("title", SearchOp.LIKE, SearchLogic.AND, "登录")))).contains("*登录*"));
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("title", SearchOp.LIKE_LEFT, SearchLogic.AND, "日志")))).contains("*日志"));
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("title", SearchOp.LIKE_RIGHT, SearchLogic.AND, "用户")))).contains("用户*"));
    }

    @Test
    void shouldBuildTermsForInAndWrapNotIn() {
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("status", SearchOp.IN, SearchLogic.AND, List.of(1, 2))))).contains("\"terms\""));
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("status", SearchOp.NOT_IN, SearchLogic.AND, List.of(1, 2)))))
                .contains("must_not"));
    }

    @Test
    void shouldBuildExistsAndNegatedExistsForNullChecks() {
        assertTrue(json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("title", SearchOp.IS_NOT_NULL, SearchLogic.AND)))).contains("\"exists\""));
        String json = json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("title", SearchOp.IS_NULL, SearchLogic.AND))));
        assertTrue(json.contains("must_not"), json);
        assertTrue(json.contains("\"exists\""), json);
    }

    @Test
    void shouldBuildMatchForFullText() {
        String json = json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("title", SearchOp.MATCH, SearchLogic.AND, "登录失败"))));
        assertTrue(json.contains("\"match\""), json);
        assertTrue(json.contains("登录失败"), json);
    }

    @Test
    void shouldFoldAndIntoMust() {
        String json = json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1),
                SearchCriteria.of("title", SearchOp.LIKE, SearchLogic.AND, "登录"))));
        assertTrue(json.contains("must"), json);
    }

    @Test
    void shouldFoldOrIntoShouldWithMinimumShouldMatch() {
        String json = json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1),
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.OR, 2))));
        assertTrue(json.contains("should"), json);
        assertTrue(json.contains("minimum_should_match"), json);
    }

    @Test
    void shouldNormalizeTemporalValueToEpochMillis() {
        LocalDateTime time = LocalDateTime.of(2026, 9, 30, 12, 0);
        String json = json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("operTime", SearchOp.GE, SearchLogic.AND, time))));
        // 时间统一转 epoch 毫秒，避免 JSON 里出现 LocalDateTime 对象导致 ES 无法解析
        assertTrue(json.matches(".*\"gte\":\\s*\\d+.*"), json);
    }

    @Test
    void shouldKeepStringValueAsString() {
        String json = json(EsJavaQueryBuilder.build(List.of(
                SearchCriteria.of("title", SearchOp.EQ, SearchLogic.AND, "登录"))));
        assertTrue(json.contains("登录"), json);
        assertNotNull(json);
    }
}
