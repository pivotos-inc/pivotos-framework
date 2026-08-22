package com.pivotos.ai.tool;

import com.alibaba.fastjson2.JSON;
import com.pivotos.ai.enums.ToolType;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.workflow.api.IWorkflowFacade;
import com.pivotos.workflow.api.dto.WorkflowInstanceDTO;
import com.pivotos.workflow.api.dto.WorkflowPendingTaskDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流类 AI 工具（S97 Hello World 起步，S99 首批业务工具做实）。
 *
 * <p>@Tool 方法经 {@code AiToolCallbackConfiguration} 的 MethodToolCallbackProvider
 * 同时供两条链路消费：MCP Server 端点（tools/list、tools/call）与对话链路
 * ChatClient toolCallbacks 挂载（同步对话）。
 *
 * <p>用户上下文口径：工具在 HTTP 请求线程内执行，依赖 LoginContext（ThreadLocal）
 * 取当前登录用户——对话同步链路与已鉴权的 MCP 会话（S98 端点防护后）均成立；
 * 极端场景上下文缺失时按未登录口径降级返回（守卫层白名单/审计不受影响）。
 *
 * <p>写操作工具（催办）按 S98 二次确认协议：@AiToolMeta(type=WRITE) + 方法签名
 * 显式 boolean confirm，confirm=true 才真实执行，否则守卫层返回预检说明。
 */
@Component
@RequiredArgsConstructor
public class WorkflowQueryTools {

    /** 工具侧分页条数上限（控制返回给模型的上下文体积） */
    private static final int MAX_PAGE_SIZE = 20;

    /** 工作流门面（可选依赖：workflow 插件未装配时降级返回，与 kb 门面降级口径一致） */
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

    @Tool(name = "queryMyPendingTasks",
            description = "分页查询当前登录用户的工作流待办任务列表（只读）：返回任务 ID、流程名称、业务 ID、"
                    + "当前节点、流程状态与创建时间。confirm 参数仅用于平台二次确认协议占位（只读工具传 false 或忽略均可）。")
    public String queryMyPendingTasks(
            @ToolParam(description = "页码，从 1 开始") int pageNum,
            @ToolParam(description = "每页条数（最大 20）") int pageSize,
            @ToolParam(description = "二次确认占位参数，只读工具传 false 即可") boolean confirm) {
        if (LoginContext.getUserId() == null) {
            return notLoggedIn();
        }
        IWorkflowFacade facade = workflowFacadeProvider.getIfAvailable();
        if (facade == null) {
            return moduleUnavailable("工作流");
        }
        PageResult<WorkflowPendingTaskDTO> page = facade.pagePendingTasks(pageNum, cap(pageSize));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pageNum", page.getPageNum());
        result.put("list", page.getList());
        return JSON.toJSONString(result);
    }

    @Tool(name = "queryMyFlowInstances",
            description = "分页查询当前登录用户发起的流程实例列表（只读）：返回实例 ID、流程名称、业务 ID、"
                    + "当前节点、流程状态与发起时间。confirm 参数仅用于平台二次确认协议占位（只读工具传 false 或忽略均可）。")
    public String queryMyFlowInstances(
            @ToolParam(description = "页码，从 1 开始") int pageNum,
            @ToolParam(description = "每页条数（最大 20）") int pageSize,
            @ToolParam(description = "二次确认占位参数，只读工具传 false 即可") boolean confirm) {
        if (LoginContext.getUserId() == null) {
            return notLoggedIn();
        }
        IWorkflowFacade facade = workflowFacadeProvider.getIfAvailable();
        if (facade == null) {
            return moduleUnavailable("工作流");
        }
        PageResult<WorkflowInstanceDTO> page = facade.pageMyInstances(pageNum, cap(pageSize));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pageNum", page.getPageNum());
        result.put("list", page.getList());
        return JSON.toJSONString(result);
    }

    @Tool(name = "urgeFlowInstance",
            description = "催办一条当前登录用户发起的流程实例（写操作）：向当前审批节点发送催办通知。"
                    + "二次确认协议：confirm=false 仅预检不执行；获得用户明确同意后以 confirm=true 重新调用才真实催办。"
                    + "限频：同一实例 10 分钟内仅可催办一次；仅发起人、仅进行中实例可催办。")
    @AiToolMeta(type = ToolType.WRITE, confirmRequired = true)
    public String urgeFlowInstance(
            @ToolParam(description = "流程实例 ID（可先通过 queryMyFlowInstances 查询）") long instanceId,
            @ToolParam(description = "二次确认标记：true 真实执行催办，false 仅预检") boolean confirm) {
        if (LoginContext.getUserId() == null) {
            return notLoggedIn();
        }
        IWorkflowFacade facade = workflowFacadeProvider.getIfAvailable();
        if (facade == null) {
            return moduleUnavailable("工作流");
        }
        facade.urgeInstance(instanceId);
        return "催办成功：已向流程实例 " + instanceId + " 的当前审批节点发送催办通知。";
    }

    private int cap(int pageSize) {
        if (pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String notLoggedIn() {
        return "当前无登录用户上下文，无法执行本操作。";
    }

    private String moduleUnavailable(String module) {
        return module + "模块未装配，本工具暂不可用。";
    }
}
