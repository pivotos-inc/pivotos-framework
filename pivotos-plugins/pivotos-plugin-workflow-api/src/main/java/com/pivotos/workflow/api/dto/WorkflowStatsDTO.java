package com.pivotos.workflow.api.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流实例统计 DTO（跨 Plugin 契约，运营工作台/数据大屏用，S71）。
 */
@Data
public class WorkflowStatsDTO {

    /** 实例总数 */
    private Long totalInstances;

    /** 待审批任务数（flow_status=1 的任务） */
    private Long pendingTasks;

    /** 实例按 flow_status 分组计数（仅含非零状态；0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回） */
    private Map<String, Long> statusCounts = new LinkedHashMap<>();
}
