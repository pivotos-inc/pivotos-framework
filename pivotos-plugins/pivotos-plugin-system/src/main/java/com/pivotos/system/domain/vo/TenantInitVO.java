package com.pivotos.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/** 租户初始化向导结果 */
@Data
@AllArgsConstructor
public class TenantInitVO {

    /** 租户ID */
    @Schema(description = "租户ID")
    private Long tenantId;

    /** 管理员用户ID */
    @Schema(description = "管理员用户ID")
    private Long adminUserId;
}
