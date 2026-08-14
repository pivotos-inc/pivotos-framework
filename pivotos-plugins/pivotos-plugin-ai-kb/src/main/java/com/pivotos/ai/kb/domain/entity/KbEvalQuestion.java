package com.pivotos.ai.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库检索评测问题实体（S66）。
 *
 * <p>评测问题集按知识库维度管理：跑分时对每题执行「rerank 关 / 开」两次检索，
 * 以 expectedKeyword 是否命中 topK 判定召回质量（Hit@K / MRR）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_eval_question")
public class KbEvalQuestion extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 关联知识库ID */
    private Long kbId;

    /** 评测问题 */
    private String question;

    /** 预期命中关键词（命中=topK 任一结果内容包含该词，忽略大小写） */
    private String expectedKeyword;

    /** 排序 */
    private Integer sort;
}
