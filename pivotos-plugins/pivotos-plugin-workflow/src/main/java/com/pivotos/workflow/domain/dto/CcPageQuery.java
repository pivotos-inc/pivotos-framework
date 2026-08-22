package com.pivotos.workflow.domain.dto;

import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 抄送我的分页查询参数（S78 F1）。
 */
@Data
public class CcPageQuery {

    /** 流程名称（模糊） */
    @Schema(description = "流程名称（模糊）")
    private String flowName;

    /** 已读标记过滤（可选）：0 未读 1 已读 */
    @Schema(description = "已读标记过滤（可选）：0 未读 1 已读")
    private Integer readFlag;

    /** 页码 */
    @Schema(description = "页码")
    private Integer pageNum = 1;

    /** 每页条数 */
    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
