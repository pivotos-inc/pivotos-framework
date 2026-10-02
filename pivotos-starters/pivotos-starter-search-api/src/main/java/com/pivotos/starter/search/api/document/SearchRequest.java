package com.pivotos.starter.search.api.document;

import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.query.SearchOrder;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索请求（Provider 输入）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
@Builder
public final class SearchRequest {

    /** 索引名 */
    private final String indexName;

    /** 条件树（按声明顺序，logic 描述与前一个条件的连接关系） */
    private final List<SearchCriteria> criteria;

    /** 排序项 */
    private final List<SearchOrder> orders;

    /** 页码（从 1 起） */
    private final int pageNum;

    /** 每页条数 */
    private final int pageSize;

    public static SearchRequest of(String indexName, List<SearchCriteria> criteria,
                                   List<SearchOrder> orders, int pageNum, int pageSize) {
        return SearchRequest.builder()
                .indexName(indexName)
                .criteria(criteria == null ? new ArrayList<>() : new ArrayList<>(criteria))
                .orders(orders == null ? new ArrayList<>() : new ArrayList<>(orders))
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }

    /**
     * 起始偏移（ES from）
     */
    public int offset() {
        return (Math.max(pageNum, 1) - 1) * Math.max(pageSize, 1);
    }
}
