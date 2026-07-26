package com.pivotos.starter.auth.account;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;

/**
 * 移动端 App 管理端账号体系：sys-user
 */
public final class StpAppUtil {

    /** 账号体系标识 */
    public static final String TYPE = "app-user";

    /** 本体系独立的 StpLogic（多账号核心：Token/会话互相隔离） */
    public static final StpLogic STP = new StpLogic(TYPE);

    private StpAppUtil() {
    }

    /**
     * 登录并返回 Token
     */
    public static String login(Object loginId) {
        STP.login(loginId);
        return STP.getTokenValue();
    }

    /**
     * 当前登录 ID，未登录返回 null
     */
    public static Object getLoginIdDefaultNull() {
        return STP.getLoginIdDefaultNull();
    }

    /**
     * 注销
     */
    public static void logout() {
        STP.logout();
    }

    /**
     * Token 会话（存放 LoginUser 等附加数据）
     */
    public static SaSession getTokenSession() {
        return STP.getTokenSession();
    }
}
