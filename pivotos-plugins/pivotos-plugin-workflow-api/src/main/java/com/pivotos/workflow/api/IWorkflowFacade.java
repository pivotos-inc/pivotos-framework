package com.pivotos.workflow.api;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.workflow.api.dto.ApprovalTaskContextDTO;
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

    // ---- S101 补充：A3 AI 审批助手待办聚合供给面 ----

    /**
     * 获取待办任务的完整审批上下文（实例信息 + 流程变量 + 审批历史）。
     * <p>归属校验：仅当前任务的待办审批人可取（口径同加签/减签归属闸）。
     *
     * @param taskId 待办任务 ID
     * @return 审批上下文；任务不存在返回 null，非本人待办抛 ServiceException
     */
    ApprovalTaskContextDTO getApprovalTaskContext(Long taskId);

    // ---- S117 补充：A4E 受控自动预审（低风险单受控自动通过） ----

    /**
     * 审批通过指定待办任务（供 A4E 受控自动预审调用）。
     *
     * <p><b>归属闸</b>：本方法不加任何「系统代审」旁路——它走的是与管理端「通过」按钮
     * 完全相同的 {@code FlowTaskService.pass}，而 warm-flow 的归属校验取自
     * {@code LoginContext}（见 {@code WorkflowConfig.PermissionHandler}）。
     * 因此本方法<b>只有在调用方请求线程里确实是审批人本人时才可能成功</b>；
     * 非审批人调用会被引擎拒（实测：非审批人 1500「无法跳转到该节点」、审批人 code=0）。
     *
     * <p>这也是本方法不做「后台扫描代审」的原因：{@code LoginContext} 是只读 ScopedValue，
     * 服务端无法以审批人身份伪造上下文，自动预审只能发生在审批人本人的请求内。
     *
     * @param taskId  待办任务 ID
     * @param message 审批意见（自动预审会写入「AI 预审自动通过」口径的意见，便于历史追溯）
     */
    void approveTask(Long taskId, String message);
}
