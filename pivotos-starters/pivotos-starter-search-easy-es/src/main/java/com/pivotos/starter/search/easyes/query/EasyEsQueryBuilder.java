package com.pivotos.starter.search.easyes.query;

import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.query.SearchOrder;
import org.dromara.easyes.core.conditions.select.LambdaEsQueryWrapper;
import org.dromara.easyes.core.kernel.EsWrappers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

/**
 * 条件树 → Easy-ES {@link LambdaEsQueryWrapper} 翻译器（纯函数，无 IO，可离线单测）。
 * <p>用 Easy-ES 原生 wrapper 承载条件，是本实现「名副其实用上 Easy-ES」的地方；
 * wrapper 产出的 {@code SearchRequest.Builder} 由 {@code BaseEsMapperImpl#getSearchBuilder} 消费。
 *
 * <p><b>为什么实体类型必须是 HashMap 而不是 Map</b>：Easy-ES 的 {@code EntityInfoHelper.getEntityInfo}
 * 会沿 {@code getSuperclass()} 向上找实体元信息，而<b>接口的 getSuperclass() 返回 null</b>，
 * 传 Map.class 会直接抛 {@code EasyEsException: Class must not be null}。故载体固定用具体类 HashMap。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EasyEsQueryBuilder {

    /** 索引内时间的字符串格式（与 es-java 侧、与「实体 → JSON → Map」后的形态三方一致） */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EasyEsQueryBuilder() {
    }

    /**
     * 构建 Easy-ES 查询 wrapper（实体载体固定 HashMap：Provider 层只处理扁平文档）
     */
    public static LambdaEsQueryWrapper<HashMap> build(List<SearchCriteria> criteria, List<SearchOrder> orders) {
        LambdaEsQueryWrapper<HashMap> wrapper = EsWrappers.lambdaQuery(HashMap.class);
        if (criteria != null) {
            for (SearchCriteria c : criteria) {
                apply(wrapper, c);
            }
        }
        if (orders != null) {
            for (SearchOrder order : orders) {
                if (order.isAsc()) {
                    wrapper.orderByAsc(order.getField());
                } else {
                    wrapper.orderByDesc(order.getField());
                }
            }
        }
        return wrapper;
    }

    private static void apply(LambdaEsQueryWrapper<HashMap> wrapper, SearchCriteria c) {
        String field = c.getField();
        // 值归一：时间对象一律转定长字符串（见 normalize 的说明），其余原样
        Object value = normalize(c.value());
        List<Object> values = normalizeAll(c.getValues());
        // OR 语义：Easy-ES wrapper 用 or() 切换后续条件的连接符
        if (c.getLogic() == SearchLogic.OR) {
            wrapper.or();
        }
        switch (c.getOp()) {
            case EQ -> wrapper.eq(field, value);
            case NE -> wrapper.not(w -> w.eq(field, value));
            case GT -> wrapper.gt(field, value);
            case GE -> wrapper.ge(field, value);
            case LT -> wrapper.lt(field, value);
            case LE -> wrapper.le(field, value);
            case LIKE -> wrapper.like(field, value);
            case LIKE_LEFT -> wrapper.likeLeft(field, value);
            case LIKE_RIGHT -> wrapper.likeRight(field, value);
            case IN -> wrapper.in(field, values);
            // Easy-ES 3.0.2 未提供 notIn / isNull 原语，用 not(...) 包裹正向条件表达（语义等价）
            case NOT_IN -> wrapper.not(w -> w.in(field, values));
            case BETWEEN -> wrapper.between(field, values.get(0), values.get(1));
            case IS_NULL -> wrapper.not(w -> w.isNotNull(field));
            case IS_NOT_NULL -> wrapper.isNotNull(field);
            // Easy-ES 的 match 走分词检索，与 simple 的「任意字段包含」语义不同，此处如实映射
            case MATCH -> wrapper.match(field, value);
            default -> throw new IllegalStateException("未支持的操作符：" + c.getOp());
        }
    }

    /**
     * 值归一化：<b>时间统一转定长字符串 {@code yyyy-MM-dd HH:mm:ss}</b>，其余原样。
     * <p>与 es-java 侧 {@code EsJavaQueryBuilder#normalize} 同口径，三方（simple / es-java / easy-es）对齐。
     * <p>为什么必须做：索引里的文档来自「实体 → JSON → Map」，时间是<b>字符串</b>；
     * 而 easy-es 把条件值直接塞进 {@code JsonData} 交给 Jackson 序列化，传 LocalDateTime 会抛
     * {@code InvalidDefinitionException: Java 8 date/time type not supported by default}
     * （ES 7.17 真机实测）——既不是「查不到」而是<b>直接报错</b>，比 es-java 侧的静默失效更早暴露。
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
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    private static List<Object> normalizeAll(List<Object> values) {
        if (values == null) {
            return List.of();
        }
        List<Object> normalized = new ArrayList<>(values.size());
        for (Object v : values) {
            normalized.add(normalize(v));
        }
        return normalized;
    }
}
