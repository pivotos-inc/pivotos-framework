package com.pivotos.ai.kb.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 检索评测问题保存请求（S66；id 为空表示新增）。
 */
@Data
public class KbEvalSaveRequest {

    /** 主键（为空=新增） */
    @Schema(description = "主键（为空=新增）")
    private Long id;

    /** 关联知识库ID */
    @NotNull(message = "知识库ID不能为空")
    private Long kbId;

    /** 评测问题 */
    @NotBlank(message = "评测问题不能为空")
    @Size(max = 500, message = "评测问题最长 500 字")
    private String question;

    /** 预期命中关键词 */
    @NotBlank(message = "预期命中关键词不能为空")
    @Size(max = 200, message = "预期命中关键词最长 200 字")
    private String expectedKeyword;

    /** 排序 */
    @Schema(description = "排序")
    private Integer sort;
}
