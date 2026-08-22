package com.pivotos.ai.tool;

import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.workflow.api.IWorkflowFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 工作流查询类 AI 工具（S97 A2 Hello World）。
 *
 * <p>@Tool 方法经 {@code AiToolCallbackConfiguration} 的 MethodToolCallbackProvider
 * 同时供两条链路消费：MCP Server 端点（tools/list、tools/call）与对话链路
 * ChatClient toolCallbacks 挂载（同步对话）。
 *
 * <p>用户上下文口径：工具在 HTTP 请求线程内执行，依赖 LoginContext（ThreadLocal）
 * 取当前登录用户——对话同步链路与已鉴权的 MCP 会话（S98 端点防护后）均成立；
 * 极端场景上下文缺失时按未登录口径返回 0（守卫层白名单/审计不受影响）。
 */
@Component
@RequiredArgsConstructor
public class WorkflowQueryTools {

    /** 工作流门面（可选依赖：workflow 插件未装配时降级返回 0，与 kb 门面降级口径一致） */
    private final ObjectProvider<IWorkflowFacade> workflowFacadeProvider;

    @Tool(name = "queryMyPendingTaskCount",
            description = "查询当前登录用户的工作流待办任务数量（只读）。返回待办任务数；未登录或上下文缺失时返回 0。"
                    + "confirm 参数仅用于平台二次确认协议占位（只读工具传 false 或忽略均可）。")
    public long queryMyPendingTaskCount(boolean confirm) {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            return 0L;
        }
        IWorkflowFacade facade = workflowFacadeProvider.getIfAvailable();
        return facade == null ? 0L : facade.countPending();
    }
}
