package com.pivotos.ai.orchestrator;

/**
 * 编排步骤上下文（A5-1 / S116）：把「当前步骤的 planId / stepNo」带进工具调用链。
 *
 * <p>为什么用 ScopedValue 而不是参数透传：工具调用的最终执行者是 {@code GuardedToolCallback}，
 * 它由 Spring AI / MCP 框架反射调用，中间没有可插参数的位置；而它写审计
 * （{@code AiToolInvokeRecorder}）时又必须知道「这一跳属于哪条编排的第几步」，
 * 否则编排调用链在审计侧就碎成一堆同名工具记录，无法还原。
 *
 * <p>绑定入口只有 {@link #runInStep(Long, int, Runnable)} 一处，且限定在同一线程同步执行
 * ——刻意如此：<code>LoginContext</code> 同样是 ScopedValue，脱离请求线程执行工具会让
 * 白名单闸报「需登录上下文」，因此编排执行器本就必须是同步内联的（见 PlanExecutor 类注释）。
 *
 * @author PivotOS
 * @since 2.15.0（S116 A5-1）
 */
public final class OrchestratorStepContext {

    /** 上下文键（绑定操作只在本类进行，业务侧只读） */
    public static final ScopedValue<StepRef> KEY = ScopedValue.newInstance();

    private OrchestratorStepContext() {
    }

    /**
     * 当前步骤归属。
     *
     * @param planId 编排计划主键
     * @param stepNo 步骤序号
     */
    public record StepRef(Long planId, int stepNo) {
    }

    /**
     * 在「某一步骤」作用域内同步执行：工具回调与其审计记录均在此作用域内完成。
     */
    public static void runInStep(Long planId, int stepNo, Runnable action) {
        ScopedValue.where(KEY, new StepRef(planId, stepNo)).run(action);
    }

    /** 当前步骤归属；不在编排作用域内返回 null（普通工具调用行为不变） */
    public static StepRef get() {
        return KEY.isBound() ? KEY.get() : null;
    }
}
