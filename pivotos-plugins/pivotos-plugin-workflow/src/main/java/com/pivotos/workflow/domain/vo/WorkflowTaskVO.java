package com.pivotos.workflow.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待办任务展示 VO。
 */
@Data
public class WorkflowTaskVO {

    private Long id;
    private Long definitionId;
    private Long instanceId;
    private String flowName;
    private String businessId;
    private String nodeCode;
    private String nodeName;
    private Integer nodeType;
    private String flowStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
