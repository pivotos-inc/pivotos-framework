package com.pivotos.system.service;

/** 微信小程序 API 客户端（code2session / 手机号换取） */
public interface WechatMiniService {

    /**
     * js_code 换 openid/unionId
     *
     * @param code uni.login() 下发的 js_code
     */
    WechatSession code2Session(String code);

    /**
     * 手机号动态令牌换手机号（getPhoneNumber 按钮的 code）
     *
     * @return 纯手机号（不带区号）
     */
    String getPhoneNumber(String phoneCode);
}
