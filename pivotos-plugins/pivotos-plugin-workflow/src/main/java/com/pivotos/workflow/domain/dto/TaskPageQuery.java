package com.pivotos.workflow.domain.dto;

import lombok.Data;

/**
 * 任务分页查询参数。
 */
@Data
public class TaskPageQuery {

    /** 流程名称（模糊） */
    private String flowName;

    /** 页码 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;
}
