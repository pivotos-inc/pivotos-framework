package com.pivotos.system.domain.dto;

import lombok.Data;

/** 部门查询（树形列表，不分页） */
@Data
public class DeptQuery {

    /** 部门名称（模糊） */
    private String deptName;

    /** 状态 */
    private Integer status;
}
