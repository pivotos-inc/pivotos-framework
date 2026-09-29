package com.pivotos.ai.orchestrator;

import com.alibaba.fastjson2.JSON;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.service.AiToolService;
import com.pivotos.ai.tool.ToolGuardSignal;
import com.pivotos.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排执行器（A5-1 / S116）——确定性的一步一步执行器。
 *
 * <p><b>为什么必须同步内联</b>：工具本体（{@code WorkflowQueryTools} / {@code MessageTools} /
 * {@code CodingFileTools}）都依赖 {@code LoginContext} 取当前用户，而它是 ScopedValue，
 * 一旦换线程就取不到，守卫会直接把调用拒在「需登录上下文」这一步；此外 ArchUnit A6 禁裸线程。
 * 因此本执行器<b>在请求线程内顺序执行，不开池、不并行</b>——并行与异步编排留 S117，
 * 届时先解决上下文传播问题。
 *
 * <p><b>三条硬闸门</b>（与 A2 既有口径逐字对齐，不做旁路）：
 * <ol>
 *   <li>每步都走 {@link AiToolService#invokeTool} → {@code GuardedToolCallback}，
 *       注册闸 / 白名单 / confirm 预检 / 审计全量生效；</li>
 *   <li>写步骤：计划期已禁掉 {@code confirm=true}，执行期<b>默认不代客勾选</b>——
 *       未显式 {@code confirmed=true} 时停在首个写步骤，状态 {@code need_confirm}，
 *       已执行步骤的输出保留（用户确认后从中断点继续）；</li>
 *   <li>失败即停：任一步返回文本命中 {@link ToolGuardSignal#isFailure(String)} 即刻终止。
 *       〔A2 把异常吞成文本的既有设计让「看返回类型判成败」不可行，守卫信号常量化是这里的关键前提。〕</li>
 * </ol>
 *
 * <p><b>成败判定为什么看文案前缀</b>：见 {@link ToolGuardSignal} 类注释——这是 MCP / REST /
 * ChatClient 三链路语义一致的代价（HTTP 层恒 code=0）。信号源已单点化，编排侧只读，不复制文案。
 *
 * @author PivotOS
 * @since 2.15.0（S116 A5-1）
 */
@Component
public class PlanExecutor {

    private final AiToolService toolService;
    private final ToolPlanValidator validator;

    public PlanExecutor(AiToolService toolService, ToolPlanValidator validator) {
        this.toolService = toolService;
        this.validator = validator;
    }

    /**
     * 执行计划。
     *
     * @param plan      待执行计划（内部会再校验一次：工具状态运行期可变）
     * @param toolSpecs 活工具目录（写标记取自此）
     * @param planId    计划主键（审计关联；由调用方落库后传入）
     * @param confirmed 是否已获得用户对写操作的二次确认
     * @param startedAt 起始时间戳（纳秒，用于统计总耗时）
     * @return 执行结果快照
     */
    public PlanRunResult execute(ToolPlan plan, Map<String, ToolSpec> toolSpecs, Long planId,
                                 boolean confirmed, long startedAt) {
        List<String> errors = validator.validate(plan, toolSpecs);
        if (!errors.isEmpty()) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_ARGS_INVALID, String.join("；", errors));
        }
        Map<Integer, String> outputs = new LinkedHashMap<>();
        for (PlanStep step : plan.steps()) {
            ToolSpec spec = toolSpecs.get(step.tool());
            if (spec != null && spec.write() && !confirmed) {
                return needConfirm(planId, outputs, step,
                        "计划第 " + step.no() + " 步「" + step.tool() + "」为写操作，需二次确认后继续");
            }
            String argsJson = renderArgs(step, outputs, spec != null && spec.write() && confirmed);
            String output = invokeInStepScope(planId, step.no(), step, argsJson);
            if (ToolGuardSignal.isNeedConfirm(output)) {
                return needConfirm(planId, outputs, step,
                        "计划第 " + step.no() + " 步「" + step.tool() + "」等待写操作确认");
            }
            if (ToolGuardSignal.isFailure(output)) {
                return PlanRunResult.builder()
                        .planId(planId)
                        .status("failed")
                        .executedSteps(outputs.size())
                        .allOutputs(outputs)
                        .summary("执行中断于第 " + step.no() + " 步「" + step.tool() + "」：" + output)
                        .costMs(elapsed(startedAt))
                        .build();
            }
            outputs.put(step.no(), output);
        }
        return PlanRunResult.builder()
                .planId(planId)
                .status("success")
                .executedSteps(outputs.size())
                .allOutputs(outputs)
                .summary("已完成 " + outputs.size() + " 步")
                .costMs(elapsed(startedAt))
                .build();
    }

    private PlanRunResult needConfirm(Long planId, Map<Integer, String> outputs, PlanStep step, String summary) {
        return PlanRunResult.builder()
                .planId(planId)
                .status("need_confirm")
                .executedSteps(outputs.size())
                .blockedStep(step.no())
                .allOutputs(outputs)
                .summary(summary)
                .costMs(0L)
                .build();
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /**
     * 渲染本步入参：把 {@code ${stepN...}} 引用替换成前序步骤的真实输出。
     *
     * @param injectConfirm 是否由编排层写入 {@code confirm=true}
     *
     * <p><b>全代码唯一一处「代用户勾 confirmed」就在这一步</b>（因此必须说清楚它的正当性）：
     * 计划期已硬性禁止模型写 {@code confirm=true}（见 {@link ToolPlanValidator#checkNoAutoConfirm}），
     * 但写工具的守卫预检只认入参里的 confirm 值——两者之间需要一个「用户已授权」的载体。
     * 这个载体就是本方法的入参 {@code injectConfirm}：它<b>只能来自 REST 入参 {@code confirmed}</b>，
     * 而那个布尔值是用户在前端把写步骤逐条看过、点了「确认并执行」之后才置 true 的（PC 页同样如此）。
     * 因此这里注入的不是「模型的自作主张」，而是「用户已给出的明确同意」；
     * {@code confirmed=false} 时写步骤连预检都不会发生——直接停在 {@code need_confirm}。
     */
    private String renderArgs(PlanStep step, Map<Integer, String> outputs, boolean injectConfirm) {
        Map<String, Object> rendered = new HashMap<>();
        for (Map.Entry<String, Object> entry : step.args().entrySet()) {
            rendered.put(entry.getKey(), PlanRefRenderer.render(entry.getValue(), outputs));
        }
        if (injectConfirm && rendered.containsKey("confirm")) {
            rendered.put("confirm", Boolean.TRUE);
        }
        return JSON.toJSONString(rendered);
    }

    /**
     * 在「编排步骤」作用域内调用工具，使审计能带出 planId / stepNo。
     *
     * <p>ScopedValue 绑定只包裹这一步的工具调用；因为它仍在同一线程同步执行，
     * 工具内部的 {@code LoginContext} 作用域不受影响（ScopedValue 是叠加而非替换）。
     */
    private String invokeInStepScope(Long planId, int stepNo, PlanStep step, String argsJson) {
        String[] holder = new String[1];
        OrchestratorStepContext.runInStep(planId, stepNo,
                () -> holder[0] = toolService.invokeTool(step.tool(), argsJson));
        return holder[0];
    }
}
