package com.pivotos.ai.orchestrator;

/**
 * 单个编排步骤的执行轨迹（A5-2 / S117）——「编排可观测」的最小完备单元。
 *
 * <p>落库载体为 {@code ai_tool_plan_step}。之所以要它：
 * {@code ai_tool_invoke} 记的是「每一次调用」，重试会让同一步出现多行调用，
 * 单看审计无法回答「这一步到底成没成、试了几次」；本对象给出的是步骤级终态。
 *
 * @param stepNo        步骤序号
 * @param tool          工具名
 * @param write         是否写操作
 * @param attemptCount  实际尝试次数（1 表示首次即成功）
 * @param status        success / failed / need_confirm / skipped
 * @param argsJson      渲染后的实际入参
 * @param outputSummary 输出摘要（超长截断）
 * @param error         失败原因（终态失败信号原文，成功为 null）
 * @param costMs        本步耗时（含重试）
 */
public record PlanStepTrace(int stepNo, String tool, boolean write, int attemptCount, String status,
                            String argsJson, String outputSummary, String error, long costMs) {

    /** 步骤级状态常量（与 ai_tool_plan_step.status 同口径，勿散落字面量） */
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String NEED_CONFIRM = "need_confirm";
    public static final String SKIPPED = "skipped";
}
