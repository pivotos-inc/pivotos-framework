package com.pivotos.ai.service;

import com.pivotos.ai.enums.ToolInvokeStatus;

/**
 * 工具调用审计记录器（S98 A2；S116 A5-1 扩展编排维度）。
 *
 * <p>编排场景下额外落 {@code plan_id} / {@code step_no}：取值来自
 * {@code OrchestratorStepContext} 的当前作用域（由 {@code PlanExecutor} 在逐步调用前绑定），
 * 记录器本身不感知编排逻辑——这样普通工具调用（对话 / MCP / REST）的行为完全不变。
 */
public interface AiToolInvokeRecorder {

    /**
     * 记录一次工具调用。
     *
     * @param toolName  工具名
     * @param args      入参原文（会自动截断）
     * @param status    调用状态
     * @param errorMsg  失败 / 拒绝原因
     * @param costMs    执行耗时（毫秒）
     */
    void record(String toolName, String args, ToolInvokeStatus status, String errorMsg, long costMs);
}
