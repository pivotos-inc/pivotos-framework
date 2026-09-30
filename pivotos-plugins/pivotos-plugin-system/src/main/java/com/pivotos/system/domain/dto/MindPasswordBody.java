package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** PivotOS·智域账密登录/注册请求体（H5 开发调试与兜底） */
@Data
public class MindPasswordBody {

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 30, message = "用户名最长 30 字符")
    private String username;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 4, max = 50, message = "密码长度 4-50 字符")
    private String password;
}
