package com.pivotos.ai.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库检索评测跑分逐题明细实体（S67）。
 *
 * <p>快照保存跑分时刻的问题与关键词，原问题删除后历史仍可完整回溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_eval_record_item")
public class KbEvalRecordItem extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 关联跑分记录ID */
    private Long recordId;

    /** 关联知识库ID */
    private Long kbId;

    /** 原评测问题ID（问题可能已被删除） */
    private Long questionId;

    /** 评测问题快照 */
    private String question;

    /** 预期命中关键词快照 */
    private String expectedKeyword;

    /** 基线首次命中排名（1-based，0=未命中） */
    private Integer baselineRank;

    /** 重排首次命中排名（0=未命中） */
    private Integer rerankRank;

    /** 是否改序（两配置 topK 序列不一致） */
    private Boolean orderChanged;
}
