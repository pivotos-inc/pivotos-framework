package com.pivotos.system.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 登录响应（Token） */
@Data
@AllArgsConstructor
public class LoginVO {

    /** 访问令牌 */
    private String token;
}
