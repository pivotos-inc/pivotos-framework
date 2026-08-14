package com.pivotos.ai.kb.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 检索评测单题跑分请求（S66）。
 */
@Data
public class KbEvalRunRequest {

    /** 评测问题ID */
    @NotNull(message = "评测问题ID不能为空")
    private Long questionId;

    /** 召回数量（默认 5） */
    private Integer topK;
}
