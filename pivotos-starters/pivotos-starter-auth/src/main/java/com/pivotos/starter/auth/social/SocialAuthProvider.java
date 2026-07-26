package com.pivotos.starter.auth.social;

import com.pivotos.common.api.context.LoginUser;

/**
 * 三方登录提供者扩展点（P1 实现）：
 * 微信小程序 code2session / 支付宝 / JustAuth 三方站点登录。
 * 每个渠道一个实现 Bean，由登录服务按 channel 路由。
 */
public interface SocialAuthProvider {

    /**
     * 渠道标识：wechat-mini / alipay-mini / github ...
     */
    String channel();

    /**
     * 用授权码换取（或注册并返回）登录用户
     *
     * @param code 前端下发的授权码（微信 js_code / oauth code）
     */
    LoginUser fetchLoginUser(String code);
}
