package com.pivotos.starter.search.api.query;

import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 搜索条件（确定性条件树的最小单元）。
 * <p>各 Provider 拿到的就是（field, op, values, logic）四元组，不接触任何 Lambda——
 * 保证「同一份查询在 simple / easy-es / es-java 三种实现下语义一致」。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
public final class SearchCriteria {

    /** 字段名（camelCase，由 Lambda 解析得出） */
    private final String field;

    /** 操作符 */
    private final SearchOp op;

    /** 与前一个条件的连接方式（首条件固定 AND） */
    private final SearchLogic logic;

    /** 归一化后的值列表：BETWEEN=2，IS_NULL/IS_NOT_NULL=0，其余 >=1 */
    private final List<Object> values;

    private SearchCriteria(String field, SearchOp op, SearchLogic logic, List<Object> values) {
        this.field = field;
        this.op = op;
        this.logic = logic;
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static SearchCriteria of(String field, SearchOp op, SearchLogic logic, List<Object> values) {
        return new SearchCriteria(field, op, logic, values == null ? List.of() : values);
    }

    public static SearchCriteria of(String field, SearchOp op, SearchLogic logic, Object... values) {
        return new SearchCriteria(field, op, logic, values == null ? List.of() : List.of(values));
    }

    /**
     * 单值条件的便捷构造（values 只有一个元素）
     */
    public Object value() {
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 取集合值（IN / NOT_IN 使用，去重并保持顺序无关性）
     */
    public Collection<Object> collection() {
        return values;
    }
}
