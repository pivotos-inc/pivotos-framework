package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 枢磐·智域小程序登录请求体 */
@Data
public class MindLoginBody {

    /** uni.login() 下发的 js_code */
    @NotBlank(message = "登录凭证 code 不能为空")
    private String code;
}
