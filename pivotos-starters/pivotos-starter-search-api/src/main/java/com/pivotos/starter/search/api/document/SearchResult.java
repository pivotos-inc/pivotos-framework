package com.pivotos.starter.search.api.document;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索结果（Provider 输出）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
@Builder
public final class SearchResult {

    /** 命中列表 */
    private final List<SearchHit> hits;

    /** 符合条件的总数（不受分页影响） */
    private final long total;

    public static SearchResult of(List<SearchHit> hits, long total) {
        return SearchResult.builder()
                .hits(hits == null ? new ArrayList<>() : new ArrayList<>(hits))
                .total(total)
                .build();
    }

    public static SearchResult empty() {
        return SearchResult.of(List.of(), 0L);
    }
}
