package com.pivotos.starter.search.api.template;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页结果。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
@Builder
public final class SearchPage<T> {

    private final List<T> records;
    private final long total;
    private final int pageNum;
    private final int pageSize;

    public static <T> SearchPage<T> of(List<T> records, long total, int pageNum, int pageSize) {
        return SearchPage.<T>builder()
                .records(records == null ? new ArrayList<>() : new ArrayList<>(records))
                .total(total)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }

    /**
     * 总页数
     */
    public long getPages() {
        if (pageSize <= 0) {
            return 0;
        }
        return (total + pageSize - 1) / pageSize;
    }
}
