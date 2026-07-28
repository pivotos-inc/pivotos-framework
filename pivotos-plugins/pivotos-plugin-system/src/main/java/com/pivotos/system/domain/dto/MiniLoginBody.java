package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 微信小程序登录请求体 */
@Data
public class MiniLoginBody {

    /** uni.login() 下发的 js_code */
    @NotBlank(message = "登录凭证 code 不能为空")
    private String code;
}
