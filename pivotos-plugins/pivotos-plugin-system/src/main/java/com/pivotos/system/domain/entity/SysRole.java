package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色实体 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseDO {

    /** 角色名称 */
    private String roleName;

    /** 角色编码（如 super_admin） */
    private String roleCode;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}
