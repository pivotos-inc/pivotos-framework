package com.pivotos.system.api.dto;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色传输对象
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleDTO extends BaseDTO {

    /** 角色名称 */
    private String roleName;

    /** 角色编码（如 super_admin） */
    private String roleCode;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0 正常 1 停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}
