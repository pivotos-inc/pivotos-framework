package com.pivotos.ai.kb.domain.vo;

import lombok.Data;

/**
 * 检索评测单题对比结果（S66）：同一问题在「rerank 关（基线）/ rerank 开」
 * 两种配置下的命中情况对比；Hit@K / MRR 由前端按问题集聚合。
 */
@Data
public class KbEvalCompareVO {

    /** 评测问题ID */
    private Long questionId;

    /** 评测问题 */
    private String question;

    /** 预期命中关键词 */
    private String expectedKeyword;

    /** 基线（rerank 关）是否命中 topK */
    private Boolean baselineHit;

    /** 基线首次命中排名（0=未命中） */
    private Integer baselineRank;

    /** 重排（rerank 开）是否命中 topK */
    private Boolean rerankHit;

    /** 重排首次命中排名（0=未命中） */
    private Integer rerankRank;

    /** 两配置 topK 内容序列是否发生变化（改序/换题） */
    private Boolean orderChanged;
}
