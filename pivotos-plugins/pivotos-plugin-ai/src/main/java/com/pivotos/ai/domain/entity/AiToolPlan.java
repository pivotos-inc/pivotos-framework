package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 工具编排计划实体（A5-1 / S116）。
 *
 * <p>一次编排 = 一行本表 + N 行 {@code ai_tool_invoke}（经 V2.1.13 扩展出的
 * {@code plan_id} / {@code step_no} 关联），从而在审计侧可完整还原调用链。
 *
 * <p>为何不复用 {@code ai_conversation}：会话表绑定「多轮对话 + 消息流」语义，
 * 而编排是「一次计划的确定性执行记录」——把两者耦合会让会话页出现既非提问也非回答的记录，
 * 也会让编排失去「同一计划反复确认/重放」所需的计划级状态机字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool_plan")
public class AiToolPlan extends TenantBaseDO {

    /** 用户原始意图 */
    private String intent;

    /** 计划目标（LLM 复述） */
    private String goal;

    /** 计划 JSON（ToolPlan 序列化产物，超长截断） */
    private String planJson;

    /** 步骤数 */
    private Integer stepCount;

    /** 计划状态：draft 待确认 / success 已完成 / need_confirm 等待写操作确认 / failed 中断 */
    private String status;

    /** 已成功执行的步骤数 */
    private Integer executedSteps;

    /** 被写操作确认闸拦下的步骤序号（0 表示未拦停） */
    private Integer blockedStep;

    /** 结果摘要 / 失败原因 */
    private String resultSummary;

    /** 总耗时（毫秒） */
    private Long costMs;

    /** 本次执行累计重试次数（A5-2 / S117；写步骤恒为 0） */
    private Integer retryCount;

    /** 是否触发熔断（A5-2 / S117：1 熔断，后续步骤未再发起调用） */
    private Integer circuitBroken;

    /** 失败原因（A5-2 / S117：终态失败信号原文） */
    private String failReason;

    /** 链路追踪 ID */
    private String traceId;
}
