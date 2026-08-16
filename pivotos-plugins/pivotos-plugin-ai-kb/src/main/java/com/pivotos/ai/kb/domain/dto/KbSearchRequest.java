package com.pivotos.ai.kb.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库检索入参。
 */
@Data
public class KbSearchRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库 ID */
    @NotNull(message = "知识库 ID 不能为空")
    private Long kbId;

    /** 查询文本 */
    @NotBlank(message = "查询文本不能为空")
    private String query;

    /** 返回条数（缺省 5） */
    @Schema(description = "返回条数（缺省 5）")
    private Integer topK = 5;
}
