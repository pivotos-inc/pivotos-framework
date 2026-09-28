package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 租户套餐实体（功能开关集合：menu_ids 存可用菜单 ID 集合） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant_package")
public class SysTenantPackage extends BaseDO {

    /** 套餐名称 */
    private String packageName;

    /** 菜单范围（JSON 数组，sys_menu.id 集合；NULL=不限制） */
    private String menuIds;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}
