package com.pivotos.system.service;

import com.pivotos.system.domain.vo.MindLoginVO;

/** PivotOS·智域认证编排（个人端独立账号体系） */
public interface MindAuthService {

    /** 渠道标识：sys_social_user.channel */
    String CHANNEL_PIVOTOS_MIND = "pivotos-mind";

    /** 小程序登录：微信 code → 自动建档/绑定 → 发 mind-user Token */
    MindLoginVO login(String code);

    /** 账密登录（H5 开发调试兜底）：用户不存在则自动注册 */
    MindLoginVO passwordLogin(String username, String password);
}
