package com.pivotos.system.service;

import com.pivotos.system.domain.vo.MiniLoginVO;

/** 微信小程序登录编排（code2session → 绑定查询 → wx-mini 体系发 Token） */
public interface MiniAuthService {

    /** 渠道标识：sys_social_user.channel */
    String CHANNEL_WECHAT_MINI = "wechat-mini";

    /**
     * 小程序登录：已绑定直接发 Token；未绑定返回 bound=false（不报错），
     * 前端据此引导手机号授权或账密绑定。
     */
    MiniLoginVO login(String code);

    /** 手机号授权登录：已注册手机号 → 绑定并登录；未注册 → 自动建档后绑定并登录 */
    MiniLoginVO phoneLogin(String loginCode, String phoneCode);

    /** 账密绑定登录：校验账密后把当前微信身份绑到该账号并登录 */
    MiniLoginVO bindAccount(String loginCode, String username, String password);
}
