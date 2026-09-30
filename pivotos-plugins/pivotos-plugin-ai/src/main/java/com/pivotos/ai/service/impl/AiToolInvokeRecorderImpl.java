package com.pivotos.ai.service.impl;

import com.pivotos.ai.domain.entity.AiToolInvoke;
import com.pivotos.ai.enums.ToolInvokeStatus;
import com.pivotos.ai.mapper.AiToolInvokeMapper;
import com.pivotos.ai.orchestrator.OrchestratorStepContext;
import com.pivotos.ai.service.AiToolInvokeRecorder;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TraceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 工具调用审计记录器实现（S98 A2；S116 A5-1 补编排维度）。
 *
 * <p>调用人 / 租户 / traceId 取自 ScopedValue 上下文（调用线程内可用；
 * 流式回调线程等无上下文场景记 null，口径同 ai_usage）。
 *
 * <p><b>S116 扩展点</b>：{@code plan_id} / {@code step_no} 取自
 * {@link OrchestratorStepContext} 的作用域值——只有 {@code PlanExecutor} 逐步调用工具的那一刻
 * 它才被绑定。因此本类对编排逻辑零感知：对话 / MCP / REST 直连这三条既有链路的写库行为逐字不变。
 */
@Service
@RequiredArgsConstructor
public class AiToolInvokeRecorderImpl implements AiToolInvokeRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiToolInvokeRecorderImpl.class);

    /** 入参摘要截断长度（对齐 ai_tool_invoke.args_summary VARCHAR(1000)） */
    private static final int ARGS_MAX_LEN = 1000;

    /** 错误信息截断长度（对齐 ai_tool_invoke.error_msg VARCHAR(500)） */
    private static final int ERROR_MAX_LEN = 500;

    private final AiToolInvokeMapper aiToolInvokeMapper;

    @Override
    public void record(String toolName, String args, ToolInvokeStatus status, String errorMsg, long costMs) {
        try {
            AiToolInvoke invoke = new AiToolInvoke();
            invoke.setToolName(toolName);
            invoke.setArgsSummary(truncate(args, ARGS_MAX_LEN));
            invoke.setInvokeStatus(status.getCode());
            invoke.setErrorMsg(truncate(errorMsg, ERROR_MAX_LEN));
            invoke.setCostMs(costMs);
            invoke.setTraceId(TraceContext.get());
            OrchestratorStepContext.StepRef step = OrchestratorStepContext.get();
            if (step != null) {
                invoke.setPlanId(step.planId());
                invoke.setStepNo(step.stepNo());
            }
            LoginUser loginUser = LoginContext.get();
            if (loginUser != null) {
                invoke.setUserId(loginUser.getUserId());
                invoke.setTenantId(loginUser.getTenantId());
            }
            aiToolInvokeMapper.insert(invoke);
        } catch (Exception e) {
            log.warn("[PivotOS] AI 工具审计落库失败（不阻断业务）：tool={} status={} err={}",
                    toolName, status.getCode(), e.getMessage());
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
