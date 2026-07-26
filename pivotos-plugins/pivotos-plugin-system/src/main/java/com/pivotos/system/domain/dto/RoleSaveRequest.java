package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** 角色新增/修改请求 */
@Data
public class RoleSaveRequest {

    /** 角色ID */
    private Long id;

    /** 角色名称 */
    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    /** 角色编码 */
    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 菜单ID集合（授权） */
    private List<Long> menuIds;
}
