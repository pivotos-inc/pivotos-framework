package com.pivotos.ai.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 工具调用审计分页查询入参（S98 A2）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiToolInvokeQuery extends PageQuery {

    /** 工具名（模糊） */
    private String toolName;

    /** 调用状态（success/fail/forbidden/need_confirm） */
    private String invokeStatus;

    /** 调用人 ID */
    private Long userId;
}
