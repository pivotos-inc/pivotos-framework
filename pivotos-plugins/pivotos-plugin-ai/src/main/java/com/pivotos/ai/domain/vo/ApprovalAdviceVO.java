package com.pivotos.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** AI 审批建议回显（S101 A3，GET /ai/approval/advice/{taskId}/latest） */
@Data
public class ApprovalAdviceVO {

    /** 建议记录 ID */
    @Schema(description = "建议记录 ID")
    private Long id;

    /** 待办任务 ID */
    @Schema(description = "待办任务 ID")
    private Long taskId;

    /** 结论（approve 建议通过 / reject 建议驳回 / need_info 需补充材料） */
    @Schema(description = "结论（approve / reject / need_info）")
    private String conclusion;

    /** 结论理由 */
    @Schema(description = "结论理由")
    private String reason;

    /** 制度依据引用（无依据为空列表） */
    @Schema(description = "制度依据引用")
    private List<ApprovalReferenceVO> references;

    /** 检索所用知识库 ID（无制度依据为 null） */
    @Schema(description = "检索所用知识库 ID")
    private Long kbId;

    /** 生成时间 */
    @Schema(description = "生成时间")
    private LocalDateTime createTime;
}
