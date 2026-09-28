package com.pivotos.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 租户视图 */
@Data
public class TenantVO {

    /** 租户ID */
    @Schema(description = "租户ID")
    private Long id;

    /** 租户编码 */
    @Schema(description = "租户编码")
    private String tenantCode;

    /** 租户名称 */
    @Schema(description = "租户名称")
    private String tenantName;

    /** 套餐ID */
    @Schema(description = "套餐ID")
    private Long packageId;

    /** 套餐名称 */
    @Schema(description = "套餐名称")
    private String packageName;

    /** 账号数上限（0=不限） */
    @Schema(description = "账号数上限（0=不限）")
    private Integer accountLimit;

    /** 已建账号数 */
    @Schema(description = "已建账号数")
    private Long accountCount;

    /** 过期时间（NULL=永不过期） */
    @Schema(description = "过期时间（NULL=永不过期）")
    private LocalDateTime expireTime;

    /** 状态（0正常 1停用） */
    @Schema(description = "状态（0正常 1停用）")
    private Integer status;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;

    /** 创建时间 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
