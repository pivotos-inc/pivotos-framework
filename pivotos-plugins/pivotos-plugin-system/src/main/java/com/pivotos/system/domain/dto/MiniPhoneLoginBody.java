package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 微信小程序手机号授权登录请求体 */
@Data
public class MiniPhoneLoginBody {

    /** uni.login() 下发的 js_code（换取 openid） */
    @NotBlank(message = "登录凭证 code 不能为空")
    private String loginCode;

    /** getPhoneNumber 按钮下发的手机号动态令牌 */
    @NotBlank(message = "手机号授权 code 不能为空")
    private String phoneCode;
}
