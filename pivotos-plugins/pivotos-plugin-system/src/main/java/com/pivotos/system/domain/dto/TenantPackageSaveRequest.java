package com.pivotos.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 租户套餐新增/修改请求 */
@Data
public class TenantPackageSaveRequest {

    /** 套餐ID（修改时必传） */
    @Schema(description = "套餐ID（修改时必传）")
    private Long id;

    /** 套餐名称 */
    @NotBlank(message = "套餐名称不能为空")
    @Size(max = 64, message = "套餐名称长度不能超过64个字符")
    private String packageName;

    /** 菜单范围（sys_menu.id 集合；空/NULL=不限制） */
    @Schema(description = "菜单范围（sys_menu.id 集合；空/NULL=不限制）")
    private List<Long> menuIds;

    /** 状态（0正常 1停用） */
    @Schema(description = "状态（0正常 1停用）")
    private Integer status;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;
}
