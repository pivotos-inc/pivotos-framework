package com.pivotos.workflow.api;

import com.pivotos.workflow.api.dto.WorkflowStatsDTO;

import java.util.Map;

/**
 * 工作流插件 Facade 契约。
 * <p>
 * 跨插件调用工作流能力的唯一入口（如消息通知集成时查询待办数量）。
 * S55 引擎底座 Sprint 先建骨架，S56/S57 逐步补充方法。
 */
public interface IWorkflowFacade {

    // ---- S56 补充：流程定义 CRUD ----

    // ---- S57 补充：审批流转 / 待办查询 ----

    /**
     * 查询指定用户的待办数量（供消息中心/首页仪表盘使用）
     *
     * @return 待办任务数
     */
    long countPending();

    /**
     * 发起流程实例（供其他插件触发工作流）
     *
     * @param flowCode   流程编码
     * @param businessId 业务 ID
     * @param variable   流程变量（可空）
     * @return 流程实例 ID
     */
    Long startInstance(String flowCode, String businessId, Map<String, Object> variable);

    // ---- S71 补充：运营统计 ----

    /**
     * 流程实例统计（实例总数 / 待审批任务数 / 按 flow_status 分组计数）
     *
     * @return 实例统计 DTO
     */
    WorkflowStatsDTO instanceStats();
}
