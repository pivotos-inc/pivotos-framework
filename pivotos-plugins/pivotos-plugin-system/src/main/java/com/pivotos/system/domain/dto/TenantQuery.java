package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 租户分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantQuery extends PageQuery {

    /** 租户编码（模糊） */
    @Schema(description = "租户编码（模糊）")
    private String tenantCode;

    /** 租户名称（模糊） */
    @Schema(description = "租户名称（模糊）")
    private String tenantName;

    /** 套餐ID */
    @Schema(description = "套餐ID")
    private Long packageId;

    /** 状态 */
    @Schema(description = "状态")
    private Integer status;
}
