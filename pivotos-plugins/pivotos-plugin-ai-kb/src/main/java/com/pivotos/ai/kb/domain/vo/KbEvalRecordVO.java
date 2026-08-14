package com.pivotos.ai.kb.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检索评测跑分记录视图对象（S67）。
 */
@Data
public class KbEvalRecordVO {

    /** 主键 */
    private Long id;

    /** 关联知识库ID */
    private Long kbId;

    /** 本轮跑分题数 */
    private Integer questionCount;

    /** 基线命中题数 */
    private Integer baselineHit;

    /** 重排命中题数 */
    private Integer rerankHit;

    /** 基线 Hit@K */
    private BigDecimal baselineHitRate;

    /** 重排 Hit@K */
    private BigDecimal rerankHitRate;

    /** 基线 MRR */
    private BigDecimal baselineMrr;

    /** 重排 MRR */
    private BigDecimal rerankMrr;

    /** 改序题数 */
    private Integer orderChangedCount;

    /** 跑分时间 */
    private LocalDateTime createTime;
}
