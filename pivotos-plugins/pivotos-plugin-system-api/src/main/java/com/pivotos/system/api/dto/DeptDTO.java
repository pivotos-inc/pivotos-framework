package com.pivotos.system.api.dto;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门传输对象
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeptDTO extends BaseDTO {

    /** 父部门 ID（0 为根） */
    private Long parentId;

    /** 部门名称 */
    private String deptName;

    /** 祖级列表（如 0,1,2，逗号分隔） */
    private String ancestors;

    /** 负责人用户 ID */
    private Long leaderId;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0 正常 1 停用） */
    private Integer status;
}
