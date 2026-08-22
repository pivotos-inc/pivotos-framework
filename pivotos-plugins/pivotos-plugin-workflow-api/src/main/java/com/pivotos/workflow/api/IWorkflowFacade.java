package com.pivotos.workflow.api;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.workflow.api.dto.WorkflowInstanceDTO;
import com.pivotos.workflow.api.dto.WorkflowPendingTaskDTO;
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

    // ---- S99 补充：A2 首批业务工具供给面（查待办 / 查实例 / 催办） ----

    /**
     * 分页查询当前登录用户的待办任务（归属过滤口径同管理端待办列表）
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 待办任务分页（未登录返回空页）
     */
    PageResult<WorkflowPendingTaskDTO> pagePendingTasks(int pageNum, int pageSize);

    /**
     * 分页查询当前登录用户发起的流程实例
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 实例分页（未登录按匿名口径返回空页）
     */
    PageResult<WorkflowInstanceDTO> pageMyInstances(int pageNum, int pageSize);

    /**
     * 催办流程实例（仅发起人、仅进行中实例，限频口径同管理端催办）
     *
     * @param instanceId 流程实例 ID
     */
    void urgeInstance(Long instanceId);
}
