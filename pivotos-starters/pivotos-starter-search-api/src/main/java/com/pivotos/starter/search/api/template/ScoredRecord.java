package com.pivotos.starter.search.api.template;

import lombok.Builder;
import lombok.Getter;

/**
 * 打分召回的单条命中（实体 + 相关性分数）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
@Builder
public final class ScoredRecord<T> {

    /** 反序列化后的实体 */
    private final T entity;

    /** 相关性分数（BM25 / ES _score / RRF 前的原始通道分） */
    private final double score;

    public static <T> ScoredRecord<T> of(T entity, double score) {
        return ScoredRecord.<T>builder().entity(entity).score(score).build();
    }
}
