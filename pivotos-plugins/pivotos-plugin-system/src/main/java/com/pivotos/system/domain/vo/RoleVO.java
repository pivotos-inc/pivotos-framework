package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色视图对象 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleVO extends BaseDTO {

    /** 角色名称 */
    private String roleName;

    /** 角色编码 */
    private String roleCode;

    /** 显示顺序 */
    private Integer sort;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;
}
