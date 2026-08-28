package com.pivotos.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** AI 审批建议制度依据引用（S101 A3，来自知识库检索片段） */
@Data
public class ApprovalReferenceVO {

    /** 命中分块 ID（ai_kb_chunk.id，可能为 null） */
    @Schema(description = "命中分块 ID")
    private Long chunkId;

    /** 来源文件名 */
    @Schema(description = "来源文件名")
    private String fileName;

    /** 原文摘录（超长截断） */
    @Schema(description = "原文摘录")
    private String quote;
}
