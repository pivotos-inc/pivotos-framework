package com.pivotos.starter.cloud.api.facade.fixture;

/**
 * 与 {@code IRerankFacade.RerankResult} 同构的 record 返回值。
 *
 * @param index 原始索引
 * @param score 相关性分数
 */
public record BizLikeScore(int index, double score) {
}
