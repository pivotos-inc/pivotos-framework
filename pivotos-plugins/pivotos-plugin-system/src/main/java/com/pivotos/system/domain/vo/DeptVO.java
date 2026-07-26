package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 部门视图对象（children 用于树形返回） */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeptVO extends BaseDTO {

    /** 父部门ID */
    private Long parentId;

    /** 部门名称 */
    private String deptName;

    /** 祖级列表 */
    private String ancestors;

    /** 负责人用户ID */
    private Long leaderId;

    /** 显示顺序 */
    private Integer sort;

    /** 状态 */
    private Integer status;

    /** 子部门 */
    private List<DeptVO> children;
}
