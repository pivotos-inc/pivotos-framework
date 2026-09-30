package com.pivotos.starter.search.easyes.query;

import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.query.SearchOrder;
import org.dromara.easyes.core.conditions.select.LambdaEsQueryWrapper;
import org.dromara.easyes.core.kernel.EsWrappers;

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
        // OR 语义：Easy-ES wrapper 用 or() 切换后续条件的连接符
        if (c.getLogic() == SearchLogic.OR) {
            wrapper.or();
        }
        switch (c.getOp()) {
            case EQ -> wrapper.eq(field, c.value());
            case NE -> wrapper.not(w -> w.eq(field, c.value()));
            case GT -> wrapper.gt(field, c.value());
            case GE -> wrapper.ge(field, c.value());
            case LT -> wrapper.lt(field, c.value());
            case LE -> wrapper.le(field, c.value());
            case LIKE -> wrapper.like(field, c.value());
            case LIKE_LEFT -> wrapper.likeLeft(field, c.value());
            case LIKE_RIGHT -> wrapper.likeRight(field, c.value());
            case IN -> wrapper.in(field, c.getValues());
            // Easy-ES 3.0.2 未提供 notIn / isNull 原语，用 not(...) 包裹正向条件表达（语义等价）
            case NOT_IN -> wrapper.not(w -> w.in(field, c.getValues()));
            case BETWEEN -> wrapper.between(field, c.getValues().get(0), c.getValues().get(1));
            case IS_NULL -> wrapper.not(w -> w.isNotNull(field));
            case IS_NOT_NULL -> wrapper.isNotNull(field);
            // Easy-ES 的 match 走分词检索，与 simple 的「任意字段包含」语义不同，此处如实映射
            case MATCH -> wrapper.match(field, c.value());
            default -> throw new IllegalStateException("未支持的操作符：" + c.getOp());
        }
    }
}
