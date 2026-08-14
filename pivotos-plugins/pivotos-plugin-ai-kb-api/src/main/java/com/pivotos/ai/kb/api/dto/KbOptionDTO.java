package com.pivotos.ai.kb.api.dto;

import lombok.Data;

/**
 * 知识库下拉选项 DTO（跨 Plugin 契约，对话页选择知识库用）。
 */
@Data
public class KbOptionDTO {

    /** 知识库 ID */
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 查询改写开关（true=检索前 LLM 改写多轮问题，S68） */
    private Boolean queryRewrite;
}
