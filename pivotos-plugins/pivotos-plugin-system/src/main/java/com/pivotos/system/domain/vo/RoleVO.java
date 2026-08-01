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

    /** 数据范围（1全部数据权限 2本部门 3本部门及以下 4仅本人 5自定义部门） */
    private Integer dataScope;

    /** 自定义部门ID集合（逗号分隔），data_scope=5时有效 */
    private String customDeptIds;
}
