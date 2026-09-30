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

    /** 是否受控自动通过（A4E / S117） */
    @Schema(description = "是否受控自动通过")
    private Boolean autoPassed;

    /** 自动预审判定原因 */
    @Schema(description = "自动预审判定原因")
    private String autoDecisionReason;

    /** 是否具备自动通过资格（供前端决定是否发起自动预审；不等于已通过） */
    @Schema(description = "是否具备受控自动通过资格")
    private Boolean autoEligible;

    /** 生成时间 */
    @Schema(description = "生成时间")
    private LocalDateTime createTime;
}
