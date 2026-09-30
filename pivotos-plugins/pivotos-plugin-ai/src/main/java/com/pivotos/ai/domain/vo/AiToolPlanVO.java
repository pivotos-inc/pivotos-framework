package com.pivotos.ai.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 编排计划视图对象（A5-1 / S116）——兼任「计划草案」与「执行结果」两种返回形态。
 *
 * <p>步骤输出 {@code steps[].output} 只在实际执行过后才有值；
 * 校验结论 {@code errors} 非空表示该计划不可执行（此时不允许进入执行环节）。
 */
@Data
public class AiToolPlanVO {

    private Long id;

    /** 用户原始意图 */
    private String intent;

    /** 计划目标 */
    private String goal;

    /** 步骤数 */
    private Integer stepCount;

    /** 状态：draft / success / need_confirm / failed */
    private String status;

    /** 已成功执行步骤数 */
    private Integer executedSteps;

    /** 被写操作确认闸拦下的步骤序号（0 未拦停） */
    private Integer blockedStep;

    /** 结果摘要 / 失败原因 */
    private String resultSummary;

    /** 总耗时（毫秒） */
    private Long costMs;

    /** 本次执行累计重试次数（A5-2 / S117；写步骤恒为 0） */
    private Integer retryCount;

    /** 是否触发熔断（A5-2 / S117） */
    private Boolean circuitBroken;

    /** 失败原因（终态失败信号原文） */
    private String failReason;

    /** 能力缺口说明（计划为空时） */
    private String unmapped;

    /** 校验结论（为空表示可安全执行） */
    private List<String> errors = new ArrayList<>();

    private List<PlanStepVO> steps = new ArrayList<>();

    private LocalDateTime createTime;

    /**
     * 单步视图。
     */
    @Data
    public static class PlanStepVO {

        private Integer no;

        private String tool;

        /** 入参原文（渲染引用后的实际入参，便于审计复盘） */
        private String args;

        private String reason;

        /** 是否写操作（写操作需二次确认） */
        private Boolean write;

        /** 执行输出（未执行为 null） */
        private String output;

        /** 实际尝试次数（A5-2；未执行为 0，写步骤恒 ≤ 1） */
        private Integer attemptCount;

        /** 步骤终态（success / failed / need_confirm / skipped；未执行为 null） */
        private String stepStatus;

        /** 本步耗时（毫秒，含重试） */
        private Long stepCostMs;

        /** 失败原因（终态失败信号原文，成功为 null） */
        private String error;
    }
}
