package com.pivotos.workflow.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批历史展示 VO。
 */
@Data
public class WorkflowHisTaskVO {

    private Long id;
    private Long instanceId;
    private Long taskId;
    private String nodeCode;
    private String nodeName;
    private String targetNodeCode;
    private String targetNodeName;
    /** 审批人 ID */
    private String approver;
    /** 流转类型：pass / reject / transfer / depute / revoke 等 */
    private String skipType;
    /** 协作类型（warm-flow CooperateType：6=加签 7=减签等，S81 补齐展示） */
    private Integer cooperateType;
    private String flowStatus;
    /** 审批意见 */
    private String message;
    private LocalDateTime createTime;
}
