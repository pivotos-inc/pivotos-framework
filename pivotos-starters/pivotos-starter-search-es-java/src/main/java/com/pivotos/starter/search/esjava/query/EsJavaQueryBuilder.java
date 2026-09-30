package com.pivotos.starter.search.esjava.query;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.ExistsQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchAllQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch._types.query_dsl.UntypedRangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.WildcardQuery;
import co.elastic.clients.json.JsonData;
import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.query.SearchCriteria;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 条件树 → elasticsearch-java {@link Query} 翻译器（纯函数，无 IO，可离线单测）。
 * <p>连接语义与 {@code SimpleCriteriaMatcher} 保持一致：左折叠，{@link SearchLogic#OR}
 * 生成 should(minimumShouldMatch=1) 嵌套 bool，AND 生成 must 嵌套 bool。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EsJavaQueryBuilder {

    /** 索引内时间的字符串格式（与 SearchEntityMapper 序列化后的形态一致，定长故字典序 = 时间序） */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EsJavaQueryBuilder() {
    }

    /**
     * 条件树 → Query（无条件时为 match_all）
     */
    public static Query build(List<SearchCriteria> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return new Query.Builder().matchAll(new MatchAllQuery.Builder().build()).build();
        }
        Query acc = null;
        for (SearchCriteria c : criteria) {
            Query current = toQuery(c);
            if (acc == null) {
                acc = current;
            } else if (c.getLogic() == SearchLogic.OR) {
                acc = new Query.Builder().bool(new BoolQuery.Builder()
                        .should(acc).should(current).minimumShouldMatch("1").build()).build();
            } else {
                acc = new Query.Builder().bool(new BoolQuery.Builder()
                        .must(acc).must(current).build()).build();
            }
        }
        return acc;
    }

    private static Query toQuery(SearchCriteria c) {
        String field = c.getField();
        return switch (c.getOp()) {
            case EQ -> term(field, c.value());
            case NE -> not(term(field, c.value()));
            case GT -> range(field, JsonData.of(normalize(c.value())), null, false, false);
            case GE -> range(field, JsonData.of(normalize(c.value())), null, true, false);
            case LT -> range(field, null, JsonData.of(normalize(c.value())), false, false);
            case LE -> range(field, null, JsonData.of(normalize(c.value())), false, true);
            case LIKE -> wildcard(field, "*" + c.value() + "*");
            case LIKE_LEFT -> wildcard(field, "*" + c.value());
            case LIKE_RIGHT -> wildcard(field, c.value() + "*");
            case IN -> terms(field, c.getValues());
            case NOT_IN -> not(terms(field, c.getValues()));
            case BETWEEN -> range(field, JsonData.of(normalize(c.getValues().get(0))),
                    JsonData.of(normalize(c.getValues().get(1))), true, true);
            case IS_NULL -> not(exists(field));
            case IS_NOT_NULL -> exists(field);
            // MATCH 对齐 simple 实现的语义：simple 下是「任一字符串字段包含」（无分词），
            // 而 ES 的 match 是分词检索，两者语义不同；索引里字符串一律 keyword，故用 wildcard 表达「包含」。
            case MATCH -> wildcard(field, "*" + c.value() + "*");
        };
    }

    // ==================== 基元 ====================

    private static Query term(String field, Object value) {
        return new Query.Builder().term(new TermQuery.Builder()
                .field(field).value(toFieldValue(value)).build()).build();
    }

    private static Query terms(String field, List<Object> values) {
        List<FieldValue> fvs = new ArrayList<>();
        if (values != null) {
            values.forEach(v -> fvs.add(toFieldValue(v)));
        }
        return new Query.Builder().terms(new TermsQuery.Builder()
                .field(field).terms(new TermsQueryField.Builder().value(fvs).build()).build()).build();
    }

    private static Query wildcard(String field, String pattern) {
        return new Query.Builder().wildcard(new WildcardQuery.Builder()
                .field(field).value(pattern).build()).build();
    }

    private static Query exists(String field) {
        return new Query.Builder().exists(new ExistsQuery.Builder().field(field).build()).build();
    }

    /**
     * 范围查询：es-java 8.x 的 RangeQuery 是 tagged union（number/date/term/untyped），
     * 字段类型在 Provider 层不可知（走的是扁平文档），故统一用 untyped + JsonData 表达。
     */
    private static Query range(String field, JsonData low, JsonData high, boolean includeLow, boolean includeHigh) {
        UntypedRangeQuery.Builder b = new UntypedRangeQuery.Builder().field(field);
        if (low != null) {
            if (includeLow) {
                b.gte(low);
            } else {
                b.gt(low);
            }
        }
        if (high != null) {
            if (includeHigh) {
                b.lte(high);
            } else {
                b.lt(high);
            }
        }
        RangeQuery rangeQuery = new RangeQuery.Builder().untyped(b.build()).build();
        return new Query.Builder().range(rangeQuery).build();
    }

    private static Query not(Query query) {
        return new Query.Builder().bool(new BoolQuery.Builder().mustNot(query).build()).build();
    }

    /**
     * 值归一化：<b>时间统一转定长字符串 {@code yyyy-MM-dd HH:mm:ss}</b>，其余原样。
     * <p>为什么不是 epoch 毫秒（2026-09-30 真机实测纠正）：索引里的文档来自
     * {@code SearchEntityMapper}（实体 → JSON → Map），时间经序列化后落到 ES 里是
     * <b>字符串</b>（dynamic_templates 又把它映射成 keyword），实测存的形态就是
     * {@code "2026-09-30 22:38:20"}。若条件侧转 epoch 毫秒，就是「数字 vs keyword 字符串」比大小，
     * 命中恒为 0 且不报错——和 simple 侧「时间字符串不参与比较」是同一类静默失效。
     * 定长格式的字典序 = 时间序，故 keyword 上的区间与排序均正确。
     */
    private static Object normalize(Object value) {
        if (value instanceof Date d) {
            return TIME_FORMAT.format(LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()));
        }
        if (value instanceof Instant i) {
            return TIME_FORMAT.format(LocalDateTime.ofInstant(i, ZoneId.systemDefault()));
        }
        if (value instanceof LocalDateTime ldt) {
            return TIME_FORMAT.format(ldt);
        }
        if (value instanceof LocalDate ld) {
            return TIME_FORMAT.format(ld.atStartOfDay());
        }
        return value == null ? "" : value;
    }

    /**
     * Object → ES FieldValue（term/terms 只接受标量，非标量一律按字符串处理）
     */
    private static FieldValue toFieldValue(Object value) {
        if (value == null) {
            return FieldValue.NULL;
        }
        if (value instanceof String s) {
            return FieldValue.of(s);
        }
        if (value instanceof Boolean b) {
            return FieldValue.of(b);
        }
        if (value instanceof Double d) {
            return FieldValue.of(d);
        }
        if (value instanceof Float f) {
            return FieldValue.of(f.doubleValue());
        }
        if (value instanceof Number n) {
            return FieldValue.of(n.longValue());
        }
        if (value instanceof TemporalAccessor || value instanceof Date) {
            return FieldValue.of(((Number) normalize(value)).longValue());
        }
        return FieldValue.of(value.toString());
    }
}
