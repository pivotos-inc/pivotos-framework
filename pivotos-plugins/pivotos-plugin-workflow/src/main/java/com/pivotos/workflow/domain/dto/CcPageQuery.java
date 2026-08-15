package com.pivotos.workflow.domain.dto;

import lombok.Data;

/**
 * 抄送我的分页查询参数（S78 F1）。
 */
@Data
public class CcPageQuery {

    /** 流程名称（模糊） */
    private String flowName;

    /** 已读标记过滤（可选）：0 未读 1 已读 */
    private Integer readFlag;

    /** 页码 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;
}
