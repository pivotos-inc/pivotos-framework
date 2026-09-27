package com.pivotos.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** 租户新增/修改请求 */
@Data
public class TenantSaveRequest {

    /** 租户ID（修改时必传） */
    @Schema(description = "租户ID（修改时必传）")
    private Long id;

    /** 租户编码 */
    @NotBlank(message = "租户编码不能为空")
    @Size(max = 64, message = "租户编码长度不能超过64个字符")
    private String tenantCode;

    /** 租户名称 */
    @NotBlank(message = "租户名称不能为空")
    @Size(max = 64, message = "租户名称长度不能超过64个字符")
    private String tenantName;

    /** 套餐ID（NULL=平台代管，不做套餐过滤） */
    @Schema(description = "套餐ID（NULL=平台代管，不做套餐过滤）")
    private Long packageId;

    /** 账号数上限（0=不限） */
    @Schema(description = "账号数上限（0=不限）")
    private Integer accountLimit;

    /** 过期时间（NULL=永不过期） */
    @Schema(description = "过期时间（NULL=永不过期）")
    private LocalDateTime expireTime;

    /** 状态（0正常 1停用） */
    @Schema(description = "状态（0正常 1停用）")
    private Integer status;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;
}
