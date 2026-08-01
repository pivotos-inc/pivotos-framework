package com.pivotos.starter.auth.support;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import com.pivotos.common.api.context.LoginUser;

/**
 * 登录会话数据工具：登录成功后将 LoginUser 写入 Token 会话，
 * 供 LoginContextFilter 在后续请求中还原上下文。
 */
public final class AuthSessionHolder {

    /** Token 会话中 LoginUser 的键 */
    public static final String LOGIN_USER_KEY = "LOGIN_USER";

    private AuthSessionHolder() {
    }

    /**
     * 登录成功后调用：把 LoginUser 绑到当前 Token 会话
     */
    public static void saveLoginUser(StpLogic stpLogic, LoginUser loginUser) {
        stpLogic.getTokenSession().set(LOGIN_USER_KEY, loginUser);
    }

    /**
     * 登录成功后调用：把 LoginUser 写入指定的 Token 会话。
     * 适用于多账号体系下需要显式指定 token session 的场景。
     */
    public static void saveLoginUser(SaSession tokenSession, LoginUser loginUser) {
        tokenSession.set(LOGIN_USER_KEY, loginUser);
    }
}
