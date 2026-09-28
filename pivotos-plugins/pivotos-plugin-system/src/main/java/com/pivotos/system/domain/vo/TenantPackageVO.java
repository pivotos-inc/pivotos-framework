package com.pivotos.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 租户套餐视图 */
@Data
public class TenantPackageVO {

    /** 套餐ID */
    @Schema(description = "套餐ID")
    private Long id;

    /** 套餐名称 */
    @Schema(description = "套餐名称")
    private String packageName;

    /** 菜单范围（sys_menu.id 集合；NULL=不限制） */
    @Schema(description = "菜单范围（sys_menu.id 集合；NULL=不限制）")
    private List<Long> menuIds;

    /** 状态（0正常 1停用） */
    @Schema(description = "状态（0正常 1停用）")
    private Integer status;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;

    /** 绑定租户数 */
    @Schema(description = "绑定租户数")
    private Long tenantCount;

    /** 创建时间 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
