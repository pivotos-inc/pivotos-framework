package com.pivotos.workflow.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程实例展示 VO。
 */
@Data
public class WorkflowInstanceVO {

    private Long id;
    private Long definitionId;
    private String flowName;
    private String businessId;
    private String nodeCode;
    private String nodeName;
    /** 流程状态：待审批 / 审批中 / 已完成 / 已驳回 / 已撤回 / 已终止 */
    private String flowStatus;
    private Integer activityStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
