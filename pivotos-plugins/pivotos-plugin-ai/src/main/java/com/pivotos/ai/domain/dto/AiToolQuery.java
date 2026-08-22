package com.pivotos.ai.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 工具分页查询入参（S98 A2）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiToolQuery extends PageQuery {

    /** 工具名（模糊） */
    private String toolName;

    /** 工具类型（read/write） */
    private String toolType;

    /** 状态（0正常 1停用） */
    private Integer status;
}
