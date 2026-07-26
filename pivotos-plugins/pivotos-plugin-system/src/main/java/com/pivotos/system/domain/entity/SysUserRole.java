package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 用户-角色关联（关联表，无审计字段） */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    /** 用户ID */
    private Long userId;

    /** 角色ID */
    private Long roleId;

    public SysUserRole() {
    }

    public SysUserRole(Long userId, Long roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }
}
