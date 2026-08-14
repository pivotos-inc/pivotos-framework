package com.pivotos.ai.kb.domain.vo;

import lombok.Data;

/**
 * 检索评测跑分逐题明细视图对象（S67，快照）。
 */
@Data
public class KbEvalRecordItemVO {

    /** 主键 */
    private Long id;

    /** 关联跑分记录ID */
    private Long recordId;

    /** 原评测问题ID（问题可能已被删除） */
    private Long questionId;

    /** 评测问题快照 */
    private String question;

    /** 预期命中关键词快照 */
    private String expectedKeyword;

    /** 基线首次命中排名（0=未命中） */
    private Integer baselineRank;

    /** 重排首次命中排名（0=未命中） */
    private Integer rerankRank;

    /** 是否改序 */
    private Boolean orderChanged;
}
