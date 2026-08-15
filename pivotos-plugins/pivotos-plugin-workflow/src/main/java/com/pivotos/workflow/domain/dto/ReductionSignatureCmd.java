package com.pivotos.workflow.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 减签命令（S82）：当前处理人从待办任务移除审批人。
 * <p>
 * 引擎语义为 warm-flow 原生 reductionSignature（his_task 留痕 cooperateType=REDUCTION_SIGNATURE）。
 * 安全底线由引擎内置：待办人不足两人时拒绝减签，保证节点不会减空。
 */
@Data
public class ReductionSignatureCmd {

    /** 待办任务 ID */
    private Long taskId;

    /** 被减签人用户 ID 集合（必填，字符串口径对齐 warm-flow handler） */
    private List<String> userIds;

    /** 减签说明（可选） */
    private String message;
}
