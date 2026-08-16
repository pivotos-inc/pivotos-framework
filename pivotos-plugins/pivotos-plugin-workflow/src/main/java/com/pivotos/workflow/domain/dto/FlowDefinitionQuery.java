package com.pivotos.workflow.domain.dto;

import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 流程定义列表查询参数。
 */
@Data
public class FlowDefinitionQuery {

    /** 流程名称（模糊） */
    @Schema(description = "流程名称（模糊）")
    private String flowName;

    /** 流程编码（模糊） */
    @Schema(description = "流程编码（模糊）")
    private String flowCode;

    /** 流程类别 */
    @Schema(description = "流程类别")
    private String category;

    /** 是否发布（0 未发布 / 1 已发布 / 9 失效） */
    @Schema(description = "是否发布（0 未发布 / 1 已发布 / 9 失效）")
    private Integer isPublish;

    /** 页码 */
    @Schema(description = "页码")
    private Integer pageNum = 1;

    /** 每页条数 */
    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
