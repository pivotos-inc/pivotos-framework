package com.pivotos.starter.search.api.template;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 打分召回结果（Top-K，不分页）。
 * <p>与 {@link SearchPage} 的差异：没有页码/页大小概念，命中已按分数降序，
 * 由调用方（通常是 RRF 融合器）决定怎么用。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
@Builder
public final class SearchScoredPage<T> {

    /** 命中列表（按分数降序） */
    private final List<ScoredRecord<T>> records;

    /** 符合条件（且分数达阈值）的总数 */
    private final long total;

    public static <T> SearchScoredPage<T> of(List<ScoredRecord<T>> records, long total) {
        return SearchScoredPage.<T>builder()
                .records(records == null ? new ArrayList<>() : new ArrayList<>(records))
                .total(total)
                .build();
    }

    public static <T> SearchScoredPage<T> empty() {
        return of(List.of(), 0L);
    }
}
