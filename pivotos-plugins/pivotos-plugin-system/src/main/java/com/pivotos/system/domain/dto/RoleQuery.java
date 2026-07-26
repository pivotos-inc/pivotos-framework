package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleQuery extends PageQuery {

    /** 角色名称（模糊） */
    private String roleName;

    /** 角色编码（模糊） */
    private String roleCode;

    /** 状态 */
    private Integer status;
}
