package com.pivotos.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 租户套餐查询 */
@Data
public class TenantPackageQuery {

    /** 套餐名称（模糊） */
    @Schema(description = "套餐名称（模糊）")
    private String packageName;

    /** 状态 */
    @Schema(description = "状态")
    private Integer status;
}
