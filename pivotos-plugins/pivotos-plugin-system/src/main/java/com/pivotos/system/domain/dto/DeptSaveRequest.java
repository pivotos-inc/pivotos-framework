package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 部门新增/修改请求 */
@Data
public class DeptSaveRequest {

    /** 部门ID */
    private Long id;

    /** 父部门ID（0为根） */
    @NotNull(message = "父部门不能为空")
    private Long parentId;

    /** 部门名称 */
    @NotBlank(message = "部门名称不能为空")
    private String deptName;

    /** 负责人用户ID */
    private Long leaderId;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0正常 1停用） */
    private Integer status;
}
