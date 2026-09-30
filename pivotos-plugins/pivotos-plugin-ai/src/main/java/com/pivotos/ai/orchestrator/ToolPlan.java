package com.pivotos.ai.orchestrator;

import java.util.Collections;
import java.util.List;

/**
 * 一次「多步工具调用」的结构化计划（A5-1 / S116）。
 *
 * <p>职责边界（沿用 S107 已拍板的 A4 路线：<b>LLM 只产结构化产物，执行动作由确定性层接管</b>）：
 * LLM 只负责产出本对象，<b>不产出任何可执行动作、不代为确认写操作</b>；
 * 计划能不能跑由 {@link ToolPlanValidator} 裁决，怎么跑由 {@link PlanExecutor} 决定。
 *
 * <p><b>必须是 record</b>：理由同 {@link PlanStep}——本对象要序列化进
 * {@code ai_tool_plan.plan_json} 并在详情页读回重放。
 *
 * @param goal     对用户意图的一句话复述
 * @param steps    有序步骤（依赖只可指向前序）
 * @param unmapped 现有工具无法覆盖的能力缺口说明；可完整编排时为空串
 */
public record ToolPlan(String goal, List<PlanStep> steps, String unmapped) {

    /**
     * 紧凑构造：steps 为 null 落成空列表（模型省略 steps 是常见形态）。
     */
    public ToolPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** 是否为空计划（规划器判定现有工具无法完成） */
    public boolean empty() {
        return steps.isEmpty();
    }
}
