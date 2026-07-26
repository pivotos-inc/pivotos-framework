package com.pivotos.system.api.dto;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 菜单/权限传输对象
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MenuDTO extends BaseDTO {

    /** 父菜单 ID（0 为根） */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** 类型（M 目录 C 菜单 F 按钮） */
    private String menuType;

    /** 路由地址 */
    private String path;

    /** 组件路径 */
    private String component;

    /** 权限标识（如 system:user:list） */
    private String perms;

    /** 图标 */
    private String icon;

    /** 显示顺序 */
    private Integer sort;

    /** 是否可见（0 显示 1 隐藏） */
    private Integer visible;

    /** 状态（0 正常 1 停用） */
    private Integer status;
}
