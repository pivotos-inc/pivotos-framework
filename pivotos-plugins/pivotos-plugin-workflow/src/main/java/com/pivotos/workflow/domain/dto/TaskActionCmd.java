package com.pivotos.workflow.domain.dto;

import lombok.Data;

import java.util.Map;

/**
 * 审批操作命令（通过 / 驳回 / 转办 / 委派通用）。
 */
@Data
public class TaskActionCmd {

    /** 任务 ID（必填） */
    private Long taskId;

    /** 审批意见 / 驳回原因 */
    private String message;

    /** 流程变量（传递给下游节点） */
    private Map<String, Object> variable;

    /** 转办/委派目标用户 ID（仅 transfer / depute 时必填） */
    private String targetUserId;
}
