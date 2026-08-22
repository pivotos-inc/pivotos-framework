package com.pivotos.workflow.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 流程实例传输对象（S99 A2 首批业务工具：查实例）
 *
 * <p>当前用户发起的一条流程实例，字段口径与实现模块 WorkflowInstanceVO 对齐，
 * 仅保留跨插件消费所需子集（契约层禁框架依赖，独立声明）。
 */
@Data
public class WorkflowInstanceDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 流程实例 ID */
    private Long instanceId;

    /** 流程名称 */
    private String flowName;

    /** 业务 ID */
    private String businessId;

    /** 当前节点名称 */
    private String nodeName;

    /** 流程状态（warm-flow flow_status 码值：0 待提交 1 审批中 2 审批通过 ...） */
    private String flowStatus;

    /** 活动状态（activity_status 码值） */
    private Integer activityStatus;

    /** 实例发起时间 */
    private LocalDateTime createTime;
}
