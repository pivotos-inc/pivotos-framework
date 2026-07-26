package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 菜单新增/修改请求 */
@Data
public class MenuSaveRequest {

    /** 菜单ID */
    private Long id;

    /** 父菜单ID（0为根） */
    @NotNull(message = "父菜单不能为空")
    private Long parentId;

    /** 菜单名称 */
    @NotBlank(message = "菜单名称不能为空")
    private String menuName;

    /** 类型（M目录 C菜单 F按钮） */
    @NotBlank(message = "菜单类型不能为空")
    private String menuType;

    /** 路由地址 */
    private String path;

    /** 组件路径 */
    private String component;

    /** 权限标识 */
    private String perms;

    /** 图标 */
    private String icon;

    /** 显示顺序 */
    private Integer sort;

    /** 是否可见（0显示 1隐藏） */
    private Integer visible;

    /** 状态（0正常 1停用） */
    private Integer status;
}
