package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 工具调用审计实体（S98 A2）
 *
 * <p>每次工具调用（含被闸拒绝/预检拦截）落一条：与 ai_usage 分工——
 * ai_usage 只记 token 消耗，本表记「谁调了什么工具、入参摘要、结果状态」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool_invoke")
public class AiToolInvoke extends TenantBaseDO {

    /** 工具名（ai_tool.tool_name） */
    private String toolName;

    /** 所属编排计划 ID（S116 A5-1；非编排调用为 NULL） */
    private Long planId;

    /** 编排步骤序号（S116 A5-1；非编排调用为 NULL） */
    private Integer stepNo;

    /** 调用人 ID（上下文缺失为 null） */
    private Long userId;

    /** 入参摘要（JSON，超长截断） */
    private String argsSummary;

    /** 调用状态（success/fail/forbidden/need_confirm，见 ToolInvokeStatus） */
    private String invokeStatus;

    /** 失败/拒绝原因 */
    private String errorMsg;

    /** 执行耗时（毫秒） */
    private Long costMs;

    /** 链路追踪 ID */
    private String traceId;
}
