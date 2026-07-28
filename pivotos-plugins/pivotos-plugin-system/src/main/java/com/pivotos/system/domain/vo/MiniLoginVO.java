package com.pivotos.system.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 小程序登录响应 */
@Data
@AllArgsConstructor
public class MiniLoginVO {

    /** 访问令牌（未绑定时为 null） */
    private String token;

    /** 是否已绑定系统账号：false → 前端引导手机号授权或账密绑定 */
    private Boolean bound;
}
