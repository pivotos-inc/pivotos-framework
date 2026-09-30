package com.pivotos.system.service;

/** 微信小程序 API 客户端（code2session / 手机号换取） */
public interface WechatMiniService {

    /** 默认小程序标识 */
    String DEFAULT_APP = "default";

    /** PivotOS·智域小程序标识 */
    String MIND_APP = "mind";

    /**
     * js_code 换 openid/unionId（使用 default 小程序配置）
     *
     * @param code uni.login() 下发的 js_code
     */
    default WechatSession code2Session(String code) {
        return code2Session(DEFAULT_APP, code);
    }

    /**
     * js_code 换 openid/unionId
     *
     * @param app  小程序应用标识（default / mind）
     * @param code uni.login() 下发的 js_code
     */
    WechatSession code2Session(String app, String code);

    /**
     * 手机号动态令牌换手机号（getPhoneNumber 按钮的 code，使用 default 小程序配置）
     *
     * @return 纯手机号（不带区号）
     */
    default String getPhoneNumber(String phoneCode) {
        return getPhoneNumber(DEFAULT_APP, phoneCode);
    }

    /**
     * 手机号动态令牌换手机号（getPhoneNumber 按钮的 code）
     *
     * @param app      小程序应用标识（default / mind）
     * @param phoneCode 手机号动态令牌
     * @return 纯手机号（不带区号）
     */
    String getPhoneNumber(String app, String phoneCode);
}
