package com.pivotos.starter.auth.account;

/**
 * 移动端登录校验工具：app-user / wx-mini-user 任一体系登录即放行。
 *
 * <p>S47（2.3-F3 H5 多账号数据联通）：Sa-Token {@code @SaCheckLogin} 的 type 是单值，
 * 注解无法表达「app-user 或 wx-mini-user 任一登录」，故移动端开放端点用本工具做
 * 程序化 OR 校验。未登录时委托 StpAppUtil.checkLogin() 抛 NotLoginException，
 * 走统一异常处理返回 1002（与注解鉴权行为一致）。
 */
public class StpMobileUtil {

    private StpMobileUtil() {
    }

    /** app-user / wx-mini-user 任一体系登录即返回；均未登录抛 NotLoginException（1002） */
    public static void checkLogin() {
        if (StpAppUtil.STP.getLoginIdDefaultNull() == null
                && StpWxMiniUtil.STP.getLoginIdDefaultNull() == null) {
            // 均未登录：借 app 体系的 checkLogin 抛出标准 NotLoginException
            StpAppUtil.STP.checkLogin();
        }
    }
}
