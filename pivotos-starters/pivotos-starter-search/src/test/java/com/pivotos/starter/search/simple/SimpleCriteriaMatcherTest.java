package com.pivotos.starter.search.simple;

import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.query.SearchCriteria;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * simple 实现的条件求值：三种实现的语义基准都在这里（ES 侧由各自的 builder 翻译，
 * simple 侧由本类求值；两者的判定口径必须对齐，故逐操作符钉死）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class SimpleCriteriaMatcherTest {

    private static Map<String, Object> doc(String title, Integer status) {
        Map<String, Object> map = new HashMap<>();
        map.put("title", title);
        map.put("status", status);
        return map;
    }

    @Test
    void shouldMatchEmptyCriteria() {
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 1), List.of()));
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 1), null));
    }

    @Test
    void shouldSupportEqualityAndInequality() {
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 1),
                List.of(SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1))));
        assertFalse(SimpleCriteriaMatcher.matches(doc("a", 2),
                List.of(SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1))));

        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 2),
                List.of(SearchCriteria.of("status", SearchOp.NE, SearchLogic.AND, 1))));
        // 字段为 null 时 NE 不成立（SQL 三值逻辑：NULL <> 1 不是 TRUE）
        assertFalse(SimpleCriteriaMatcher.matches(doc("a", null),
                List.of(SearchCriteria.of("status", SearchOp.NE, SearchLogic.AND, 1))));
    }

    @Test
    void shouldSupportNumericRange() {
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 5),
                List.of(SearchCriteria.of("status", SearchOp.GT, SearchLogic.AND, 3))));
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 3),
                List.of(SearchCriteria.of("status", SearchOp.GE, SearchLogic.AND, 3))));
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 2),
                List.of(SearchCriteria.of("status", SearchOp.LT, SearchLogic.AND, 3))));
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 3),
                List.of(SearchCriteria.of("status", SearchOp.LE, SearchLogic.AND, 3))));
    }

    @Test
    void shouldSupportBetween() {
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 5),
                List.of(SearchCriteria.of("status", SearchOp.BETWEEN, SearchLogic.AND, 1, 10))));
        assertFalse(SimpleCriteriaMatcher.matches(doc("a", 50),
                List.of(SearchCriteria.of("status", SearchOp.BETWEEN, SearchLogic.AND, 1, 10))));
        // 值缺失时条件不成立（不静默放行）
        assertFalse(SimpleCriteriaMatcher.matches(doc("a", null),
                List.of(SearchCriteria.of("status", SearchOp.BETWEEN, SearchLogic.AND, 1, 10))));
    }

    @Test
    void shouldSupportLikeVariantsCaseInsensitive() {
        assertTrue(SimpleCriteriaMatcher.matches(doc("用户登录日志", 1),
                List.of(SearchCriteria.of("title", SearchOp.LIKE, SearchLogic.AND, "登录"))));
        assertTrue(SimpleCriteriaMatcher.matches(doc("用户登录日志", 1),
                List.of(SearchCriteria.of("title", SearchOp.LIKE_RIGHT, SearchLogic.AND, "用户"))));
        assertTrue(SimpleCriteriaMatcher.matches(doc("用户登录日志", 1),
                List.of(SearchCriteria.of("title", SearchOp.LIKE_LEFT, SearchLogic.AND, "日志"))));
        assertFalse(SimpleCriteriaMatcher.matches(doc("用户登录日志", 1),
                List.of(SearchCriteria.of("title", SearchOp.LIKE_RIGHT, SearchLogic.AND, "登录"))));
    }

    @Test
    void shouldSupportInAndNotIn() {
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 2),
                List.of(SearchCriteria.of("status", SearchOp.IN, SearchLogic.AND, List.of(1, 2, 3)))));
        // NOT_IN：值不在集合内才命中
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 9),
                List.of(SearchCriteria.of("status", SearchOp.NOT_IN, SearchLogic.AND, List.of(1, 2, 3)))));
        assertFalse(SimpleCriteriaMatcher.matches(doc("a", 2),
                List.of(SearchCriteria.of("status", SearchOp.NOT_IN, SearchLogic.AND, List.of(1, 2, 3)))));
    }

    @Test
    void shouldSupportNullOperators() {
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", null),
                List.of(SearchCriteria.of("status", SearchOp.IS_NULL, SearchLogic.AND))));
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 1),
                List.of(SearchCriteria.of("status", SearchOp.IS_NOT_NULL, SearchLogic.AND))));
    }

    @Test
    void shouldSupportMatchFallbackOverAllStringFields() {
        Map<String, Object> map = doc("用户登录", 1);
        map.put("operator", "张三");
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("title", SearchOp.MATCH, SearchLogic.AND, "张三"))));
        assertFalse(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("title", SearchOp.MATCH, SearchLogic.AND, "李四"))));
    }

    @Test
    void shouldFoldAndOrInDeclarationOrder() {
        // status=1 OR status=2 → 命中 2
        assertTrue(SimpleCriteriaMatcher.matches(doc("a", 2), List.of(
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1),
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.OR, 2))));
        // (status=1 OR status=2) AND title like 登录 → 左折叠后 AND 收敛，未命中
        assertFalse(SimpleCriteriaMatcher.matches(doc("退出", 2), List.of(
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1),
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.OR, 2),
                SearchCriteria.of("title", SearchOp.LIKE, SearchLogic.AND, "登录"))));
        assertTrue(SimpleCriteriaMatcher.matches(doc("登录", 2), List.of(
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1),
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.OR, 2),
                SearchCriteria.of("title", SearchOp.LIKE, SearchLogic.AND, "登录"))));
    }

    @Test
    void shouldCompareTemporalValues() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        Map<String, Object> map = new HashMap<>();
        map.put("operTime", base);
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.GE, SearchLogic.AND, base.minusDays(1)))));
        assertFalse(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.LT, SearchLogic.AND, base.minusDays(1)))));
    }

    // ==================== S122：时间字符串 ↔ 时间对象归一 ====================
    // 文档侧时间在「实体 → JSON → Map」后落成字符串（fastjson2：2026-09-30 10:30:15），
    // 条件侧是 LocalDateTime 对象；不归一则时间条件在 simple 实现下恒不成立（返回空结果集）。

    @Test
    void shouldCompareSpaceSeparatedTimeStringWithLocalDateTimeCriteria() {
        Map<String, Object> map = new HashMap<>();
        map.put("operTime", "2026-09-30 10:30:15");
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 30, 23, 59, 59);
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND, start, end))));
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.GE, SearchLogic.AND, start))));
        assertFalse(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.LT, SearchLogic.AND, start))));
    }

    @Test
    void shouldCompareIsoTSeparatedTimeString() {
        Map<String, Object> map = new HashMap<>();
        map.put("operTime", "2026-09-30T10:30:15");
        LocalDateTime start = LocalDateTime.of(2026, 9, 30, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 0, 0);
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND, start, end))));
    }

    @Test
    void shouldCompareOffsetAndInstantTimeString() {
        Map<String, Object> map = new HashMap<>();
        map.put("operTime", "2026-09-30T10:30:15+08:00");
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.GT, SearchLogic.AND,
                        java.time.Instant.parse("2026-09-30T00:00:00Z")))));

        Map<String, Object> instantDoc = new HashMap<>();
        instantDoc.put("operTime", "2026-09-30T02:30:15Z");
        assertFalse(SimpleCriteriaMatcher.matches(instantDoc,
                List.of(SearchCriteria.of("operTime", SearchOp.LT, SearchLogic.AND,
                        java.time.Instant.parse("2026-09-30T02:00:00Z")))));
    }

    @Test
    void shouldComparePureDateStringWithLocalDateCriteria() {
        Map<String, Object> map = new HashMap<>();
        map.put("operTime", "2026-09-30");
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.GE, SearchLogic.AND,
                        java.time.LocalDate.of(2026, 9, 1)))));
        assertFalse(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.LT, SearchLogic.AND,
                        java.time.LocalDate.of(2026, 9, 1)))));
    }

    @Test
    void shouldTreatUnparsableTextAsNotComparableWhenOtherSideIsTemporal() {
        // 一侧是真时间、另一侧是解析不出的文本：不可比 → 条件不成立（不退化成字符串比较而意外命中）
        Map<String, Object> map = new HashMap<>();
        map.put("operTime", "不是时间");
        assertFalse(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.GE, SearchLogic.AND,
                        LocalDateTime.of(2026, 1, 1, 0, 0)))));
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("operTime", SearchOp.IS_NOT_NULL, SearchLogic.AND))));
    }

    @Test
    void shouldKeepStringOrderingForNonTemporalText() {
        // 非时间文本仍走字符串比较（String vs String 语义不变）
        Map<String, Object> map = new HashMap<>();
        map.put("module", "用户管理");
        assertTrue(SimpleCriteriaMatcher.matches(map,
                List.of(SearchCriteria.of("module", SearchOp.GT, SearchLogic.AND, "aaa"))));
    }
}
