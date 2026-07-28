package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 菜单/权限实体 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseDO {

    /** 父菜单ID（0为根） */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** 类型（M目录 C菜单 F按钮） */
    private String menuType;

    /** 路由地址 */
    private String path;

    /** 组件路径 */
    private String component;

    /** 权限标识（如 system:user:list） */
    private String perms;

    /** 图标 */
    private String icon;

    /** 可见端（pc/app/mini 逗号分隔） */
    private String device;

    /** 显示顺序 */
    private Integer sort;

    /** 是否可见（0显示 1隐藏） */
    private Integer visible;

    /** 状态（0正常 1停用） */
    private Integer status;
}
