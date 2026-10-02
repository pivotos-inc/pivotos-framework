package com.pivotos.starter.search.api.query;

import lombok.Getter;

/**
 * 排序项。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
public final class SearchOrder {

    private final String field;
    private final boolean asc;

    private SearchOrder(String field, boolean asc) {
        this.field = field;
        this.asc = asc;
    }

    public static SearchOrder asc(String field) {
        return new SearchOrder(field, true);
    }

    public static SearchOrder desc(String field) {
        return new SearchOrder(field, false);
    }
}
