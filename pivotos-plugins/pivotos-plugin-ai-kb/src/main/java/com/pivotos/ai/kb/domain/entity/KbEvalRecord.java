package com.pivotos.ai.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 知识库检索评测跑分记录实体（S67）。
 *
 * <p>每轮全量跑分落库一条聚合记录（指标由后端统一计算），逐题结果以快照明细存于
 * ai_kb_eval_record_item，题目事后修改/删除不影响历史。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_eval_record")
public class KbEvalRecord extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 关联知识库ID */
    private Long kbId;

    /** 本轮跑分题数 */
    private Integer questionCount;

    /** 基线（rerank 关）命中题数 */
    private Integer baselineHit;

    /** 重排（rerank 开）命中题数 */
    private Integer rerankHit;

    /** 基线 Hit@K（命中数/题数） */
    private BigDecimal baselineHitRate;

    /** 重排 Hit@K */
    private BigDecimal rerankHitRate;

    /** 基线 MRR（avg(1/rank)，未命中计 0） */
    private BigDecimal baselineMrr;

    /** 重排 MRR */
    private BigDecimal rerankMrr;

    /** 改序题数（两配置 topK 序列不一致） */
    private Integer orderChangedCount;
}
