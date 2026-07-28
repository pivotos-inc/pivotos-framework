package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 微信小程序账密绑定请求体（已注册员工把微信绑到自己账号上） */
@Data
public class MiniBindBody {

    /** uni.login() 下发的 js_code（换取 openid） */
    @NotBlank(message = "登录凭证 code 不能为空")
    private String loginCode;

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    private String password;
}
