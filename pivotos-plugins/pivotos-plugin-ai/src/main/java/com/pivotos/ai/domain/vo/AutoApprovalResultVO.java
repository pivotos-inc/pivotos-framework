package com.pivotos.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 受控自动预审执行结果（A4E / S117）。
 *
 * <p>无论通过与否都返回本对象：{@code autoPassed=false} 时必须能读出
 * 「为什么没通过」（{@code reason}）与「命中了哪几条规则」（{@code ruleHits}）——
 * 自动预审是代审批人做动作的能力，判定过程必须可审计，不能只回一个布尔。
 */
@Data
public class AutoApprovalResultVO {

    /** 待办任务 ID */
    @Schema(description = "待办任务 ID")
    private Long taskId;

    /** 是否已自动通过 */
    @Schema(description = "是否已受控自动通过")
    private Boolean autoPassed;

    /** 判定原因（未通过时说明哪条规则不满足；通过时为命中明细） */
    @Schema(description = "判定原因")
    private String reason;

    /** 规则命中明细 */
    @Schema(description = "规则命中明细")
    private List<String> ruleHits;

    /** 建议记录 ID（无建议为 null） */
    @Schema(description = "依据的建议记录 ID")
    private Long adviceId;
}
