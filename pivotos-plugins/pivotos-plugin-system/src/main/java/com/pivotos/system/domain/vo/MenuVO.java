package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 菜单视图对象（children 用于树形返回） */
@Data
@EqualsAndHashCode(callSuper = true)
public class MenuVO extends BaseDTO {

    /** 父菜单ID */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** 类型（M目录 C菜单 F按钮） */
    private String menuType;

    /** 路由地址 */
    private String path;

    /** 组件路径 */
    private String component;

    /** 权限标识 */
    private String perms;

    /** 图标 */
    private String icon;

    /** 可见端（pc/app/mini 逗号分隔） */
    private String device;

    /** 显示顺序 */
    private Integer sort;

    /** 是否可见 */
    private Integer visible;

    /** 状态 */
    private Integer status;

    /** 子菜单 */
    private List<MenuVO> children;
}
