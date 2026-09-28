package com.pivotos.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 租户初始化向导请求（建租户 → 配套餐 → 建管理员，单事务） */
@Data
public class TenantInitRequest {

    /** 租户编码 */
    @NotBlank(message = "租户编码不能为空")
    @Size(max = 64, message = "租户编码长度不能超过64个字符")
    private String tenantCode;

    /** 租户名称 */
    @NotBlank(message = "租户名称不能为空")
    @Size(max = 64, message = "租户名称长度不能超过64个字符")
    private String tenantName;

    /** 套餐ID */
    @NotNull(message = "套餐不能为空")
    @Schema(description = "套餐ID")
    private Long packageId;

    /** 账号数上限（0=不限） */
    @Schema(description = "账号数上限（0=不限）")
    private Integer accountLimit;

    /** 过期时间（NULL=永不过期） */
    @Schema(description = "过期时间（NULL=永不过期）")
    private LocalDateTime expireTime;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;

    /** 管理员用户名 */
    @NotBlank(message = "管理员用户名不能为空")
    @Size(max = 64, message = "管理员用户名长度不能超过64个字符")
    private String adminUsername;

    /** 管理员昵称 */
    @NotBlank(message = "管理员昵称不能为空")
    @Size(max = 64, message = "管理员昵称长度不能超过64个字符")
    private String adminNickname;

    /** 管理员初始密码（留空用系统初始密码配置） */
    @Schema(description = "管理员初始密码（留空用系统初始密码配置）")
    private String adminPassword;

    /** 管理员角色ID集合（平台既有角色；可空） */
    @Schema(description = "管理员角色ID集合（平台既有角色；可空）")
    private List<Long> adminRoleIds;
}
