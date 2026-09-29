package com.pivotos.ai.orchestrator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次编排执行的结果快照（A5-1 / S116）。
 *
 * @param planId        计划主键（落 ai_tool_plan）
 * @param status        终态：success / need_confirm / failed
 * @param executedSteps 已成功执行的步骤数（失败或拦停时小于总步数）
 * @param blockedStep   被写操作确认闸拦下的步骤序号（未拦停为 0）
 * @param outputs       已执行步骤的输出（步骤序号 → 工具返回原文）
 * @param summary       人类可读的结果摘要（失败 / 待确认时说明原因）
 * @param costMs        总耗时（毫秒）
 */
public record PlanRunResult(Long planId, String status, int executedSteps, int blockedStep,
                            Map<Integer, String> outputs, String summary, long costMs) {

    /** 是否停在写步骤等确认 */
    public boolean needConfirm() {
        return "need_confirm".equals(status);
    }

    /** 是否整链跑完 */
    public boolean success() {
        return "success".equals(status);
    }

    /** 已执行步骤序号（按序） */
    public List<Integer> stepNos() {
        return outputs.keySet().stream().toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long planId;
        private String status = "failed";
        private int executedSteps;
        private int blockedStep;
        private long costMs;
        private String summary = "";
        private final Map<Integer, String> outputs = new LinkedHashMap<>();

        public Builder planId(Long value) {
            this.planId = value;
            return this;
        }

        public Builder status(String value) {
            this.status = value;
            return this;
        }

        public Builder executedSteps(int value) {
            this.executedSteps = value;
            return this;
        }

        public Builder blockedStep(int value) {
            this.blockedStep = value;
            return this;
        }

        public Builder costMs(long value) {
            this.costMs = value;
            return this;
        }

        public Builder summary(String value) {
            this.summary = value;
            return this;
        }

        public Builder output(int stepNo, String value) {
            this.outputs.put(stepNo, value);
            return this;
        }

        public Builder allOutputs(Map<Integer, String> values) {
            this.outputs.clear();
            if (values != null) {
                this.outputs.putAll(values);
            }
            return this;
        }

        public PlanRunResult build() {
            return new PlanRunResult(planId, status, executedSteps, blockedStep,
                    new LinkedHashMap<>(outputs), summary, costMs);
        }
    }
}
