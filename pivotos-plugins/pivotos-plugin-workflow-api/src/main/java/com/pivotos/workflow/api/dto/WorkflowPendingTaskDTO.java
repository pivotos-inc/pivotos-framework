package com.pivotos.workflow.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 待办任务传输对象（S99 A2 首批业务工具：查待办）
 *
 * <p>用户视角的一条待办审批任务，字段口径与实现模块 WorkflowTaskVO 对齐，
 * 仅保留跨插件消费所需子集（契约层禁框架依赖，独立声明）。
 */
@Data
public class WorkflowPendingTaskDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 任务 ID */
    private Long taskId;

    /** 流程实例 ID */
    private Long instanceId;

    /** 流程名称 */
    private String flowName;

    /** 业务 ID */
    private String businessId;

    /** 当前节点名称 */
    private String nodeName;

    /** 流程状态（warm-flow flow_status 码值） */
    private String flowStatus;

    /** 任务创建时间 */
    private LocalDateTime createTime;
}
