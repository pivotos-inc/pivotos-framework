package com.pivotos.ai.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次编排执行的结果快照（A5-1 / S116；S117 A5-2 扩展可观测字段）。
 *
 * @param planId        计划主键（落 ai_tool_plan）
 * @param status        终态：success / need_confirm / failed
 * @param executedSteps 已成功执行的步骤数（失败或拦停时小于总步数）
 * @param blockedStep   被写操作确认闸拦下的步骤序号（未拦停为 0）
 * @param outputs       已执行步骤的输出（步骤序号 → 工具返回原文）
 * @param summary       人类可读的结果摘要（失败 / 待确认时说明原因）
 * @param costMs        总耗时（毫秒）
 * @param retryCount    本次执行累计重试次数（写步骤恒为 0）
 * @param circuitBroken 是否触发熔断（重试预算耗尽）
 * @param traces        步骤级轨迹（含因失败/熔断而未执行的 skipped 步骤）
 */
public record PlanRunResult(Long planId, String status, int executedSteps, int blockedStep,
                            Map<Integer, String> outputs, String summary, long costMs,
                            int retryCount, boolean circuitBroken, List<PlanStepTrace> traces) {

    /** 是否因熔断中止（后续步骤未再发起调用，轨迹里体现为 skipped） */
    public boolean circuitOpen() {
        return circuitBroken;
    }

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
        private int retryCount;
        private boolean circuitBroken;
        private final Map<Integer, String> outputs = new LinkedHashMap<>();
        private final List<PlanStepTrace> traces = new ArrayList<>();

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

        public Builder retryCount(int value) {
            this.retryCount = value;
            return this;
        }

        public Builder circuitBroken(boolean value) {
            this.circuitBroken = value;
            return this;
        }

        public Builder trace(PlanStepTrace value) {
            if (value != null) {
                this.traces.add(value);
            }
            return this;
        }

        public Builder allTraces(List<PlanStepTrace> values) {
            this.traces.clear();
            if (values != null) {
                this.traces.addAll(values);
            }
            return this;
        }

        public PlanRunResult build() {
            return new PlanRunResult(planId, status, executedSteps, blockedStep,
                    new LinkedHashMap<>(outputs), summary, costMs, retryCount, circuitBroken,
                    new ArrayList<>(traces));
        }
    }
}
