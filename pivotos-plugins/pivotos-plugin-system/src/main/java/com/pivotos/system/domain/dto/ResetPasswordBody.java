package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 重置密码请求 */
@Data
public class ResetPasswordBody {

    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 新密码 */
    @NotBlank(message = "新密码不能为空")
    private String password;
}
