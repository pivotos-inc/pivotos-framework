package com.pivotos.starter.search.api.document;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 检索命中结果（读取侧）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
@Builder
public final class SearchHit {

    /** 文档 ID */
    private final String id;

    /** 相关性得分（simple 实现恒为 0） */
    private final double score;

    /** 文档内容 */
    private final Map<String, Object> source;

    public static SearchHit of(String id, double score, Map<String, Object> source) {
        return SearchHit.builder().id(id).score(score).source(source).build();
    }
}
