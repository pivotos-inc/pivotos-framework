package com.pivotos.ai.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** AI 审批建议生成请求（S101 A3） */
@Data
public class ApprovalAdviceRequest {

    /** 待办任务 ID（warm-flow flow_task.id，归属校验在 workflow 侧） */
    @NotNull(message = "待办任务 ID 不能为空")
    @Schema(description = "待办任务 ID")
    private Long taskId;

    /** 知识库 ID（空 = 走默认库策略：配置项 → 首个启用库 → 无制度依据降级） */
    @Schema(description = "知识库 ID（空则走默认库策略）")
    private Long kbId;
}
