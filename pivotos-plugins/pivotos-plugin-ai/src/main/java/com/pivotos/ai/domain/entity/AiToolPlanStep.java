package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 工具编排步骤轨迹实体（A5-2 / S117）——编排可观测的落库载体。
 *
 * <p>与 {@code ai_tool_invoke} 的分工：后者记「每一次工具调用」（重试会产生多行），
 * 本表记「每一个编排步骤的终态」（含尝试次数与因熔断而未发起的 skipped 步骤），
 * 一次执行结束后可按 {@code plan_id} 完整还原「哪一步跑了几次、耗时多久、为什么停」。
 *
 * <p>status 取值：{@code success} / {@code failed} / {@code need_confirm} / {@code skipped}。
 * {@code skipped} 只在熔断后出现，语义是「未发起调用」——它与 failed 的区别必须可区分，
 * 否则用户会把「根本没跑」误读成「跑了但失败」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool_plan_step")
public class AiToolPlanStep extends TenantBaseDO {

    /** 所属编排计划 ID */
    private Long planId;

    /** 步骤序号（计划内唯一） */
    private Integer stepNo;

    /** 工具名 */
    private String toolName;

    /** 是否写操作（1 写 0 只读） */
    private Integer writeFlag;

    /** 实际尝试次数（1 表示首次即成功，写步骤恒为 1） */
    private Integer attemptCount;

    /** 步骤终态：success / failed / need_confirm / skipped */
    private String status;

    /** 渲染引用后的实际入参（审计复盘） */
    private String argsJson;

    /** 步骤输出摘要（超长截断） */
    private String outputSummary;

    /** 失败原因（终态失败信号原文，超长截断） */
    private String errorMessage;

    /** 本步耗时（毫秒，含重试） */
    private Long costMs;

    /** 链路追踪 ID */
    private String traceId;
}
