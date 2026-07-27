package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 角色-菜单关联（关联表，无审计字段） */
@Data
@TableName("sys_role_menu")
public class SysRoleMenu {

    /** 主键（雪花） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色ID */
    private Long roleId;

    /** 菜单ID */
    private Long menuId;

    public SysRoleMenu() {
    }

    public SysRoleMenu(Long roleId, Long menuId) {
        this.roleId = roleId;
        this.menuId = menuId;
    }
}
