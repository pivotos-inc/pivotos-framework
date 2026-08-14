package com.pivotos.ai.kb.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索评测问题视图对象（S66）。
 */
@Data
public class KbEvalQuestionVO {

    /** 主键 */
    private Long id;

    /** 关联知识库ID */
    private Long kbId;

    /** 评测问题 */
    private String question;

    /** 预期命中关键词 */
    private String expectedKeyword;

    /** 排序 */
    private Integer sort;

    /** 创建时间 */
    private LocalDateTime createTime;
}
