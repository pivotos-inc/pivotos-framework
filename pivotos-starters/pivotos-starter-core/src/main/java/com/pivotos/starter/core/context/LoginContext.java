package com.pivotos.starter.core.context;

import com.pivotos.common.api.context.LoginUser;

/**
 * 登录上下文静态门面（ScopedValue 实现，虚拟线程天然安全）。
 * 绑定入口仅 starter-auth 登录拦截器使用，业务侧只读。
 */
public final class LoginContext {

    /** 上下文键，绑定操作只允许 starter-auth 使用 */
    public static final ScopedValue<LoginUser> KEY = ScopedValue.newInstance();

    private LoginContext() {
    }

    /**
     * 当前登录用户，未登录返回 null
     */
    public static LoginUser get() {
        return KEY.isBound() ? KEY.get() : null;
    }

    /**
     * 当前登录用户 ID，未登录返回 null
     */
    public static Long getUserId() {
        LoginUser user = get();
        return user == null ? null : user.getUserId();
    }

    /**
     * 当前登录用户名，未登录返回 null
     */
    public static String getUsername() {
        LoginUser user = get();
        return user == null ? null : user.getUsername();
    }

    /**
     * 是否已登录
     */
    public static boolean isLogin() {
        return KEY.isBound();
    }
}
