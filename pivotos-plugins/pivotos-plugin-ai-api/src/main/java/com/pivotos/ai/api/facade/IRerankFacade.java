package com.pivotos.ai.api.facade;

import java.util.List;

/**
 * ai 插件对外契约：文本重排（rerank）能力入口（S65）。
 * 由 plugin-ai 基于数据库驱动的供应商/Key 配置实现（DashScope OpenAI 兼容 reranks 协议），
 * 供 plugin-ai-kb 等消费方在检索链路尾部精排。
 *
 * <p>降级约定：无可用配置或调用失败一律返回空列表，调用方保持原顺序，不阻断主流程。
 */
public interface IRerankFacade {

    /**
     * 对候选文档按与 query 的语义相关性精排。
     *
     * @param query     查询文本
     * @param documents 候选文档列表（按原召回顺序）
     * @param topN      返回条数（大于文档数时返回全部）
     * @return 按相关性降序的重排结果；无可用配置或调用失败返回空列表
     */
    List<RerankResult> rerank(String query, List<String> documents, int topN);

    /**
     * 重排结果。
     *
     * @param index 对应入参 documents 的原始索引
     * @param score 相关性分数（0~1，仅同一次请求内可比）
     */
    record RerankResult(int index, double score) {}
}
