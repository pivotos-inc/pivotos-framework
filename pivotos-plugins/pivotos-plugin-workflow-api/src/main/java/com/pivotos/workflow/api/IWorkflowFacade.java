package com.pivotos.workflow.api;

/**
 * 工作流插件 Facade 契约。
 * <p>
 * 跨插件调用工作流能力的唯一入口（如消息通知集成时查询待办数量）。
 * S55 引擎底座 Sprint 先建骨架，S56/S57 逐步补充方法。
 */
public interface IWorkflowFacade {

    // ---- S56 补充：流程定义 CRUD ----

    // ---- S57 补充：审批流转 / 待办查询 ----
}
