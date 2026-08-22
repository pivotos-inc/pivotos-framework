package com.pivotos.ai.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 工具调用审计视图（S98 A2）
 */
@Data
public class AiToolInvokeVO {

    /** 审计记录 ID */
    private Long id;

    /** 工具名 */
    private String toolName;

    /** 调用人 ID */
    private Long userId;

    /** 入参摘要 */
    private String argsSummary;

    /** 调用状态（success/fail/forbidden/need_confirm） */
    private String invokeStatus;

    /** 失败/拒绝原因 */
    private String errorMsg;

    /** 执行耗时（毫秒） */
    private Long costMs;

    /** 链路追踪 ID */
    private String traceId;

    /** 调用时间 */
    private LocalDateTime createTime;
}
