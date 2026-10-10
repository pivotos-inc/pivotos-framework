package com.pivotos.starter.cloud.api.context;

import lombok.Data;

/**
 * 上下文传播开关（{@code pivotos.cloud.context.*}）。
 *
 * <p><b>默认口径 = 最小权限</b>：出站传播默认开（把本进程的租户/身份带给下游，
 * 这是微服务必须的），入站恢复默认<b>关</b>，且开启时强制校验内部调用凭证
 * （{@link CloudHeaders#INTERNAL_TOKEN}）。
 *
 * <p>理由：入站恢复一旦放开，任何能直达服务端口的调用方都能伪造
 * {@code X-PivotOS-User-Id} 成为任意用户。这个功能「没开」只是功能缺失，
 * 「开错」是越权漏洞——故默认关、开了要凭证、凭证没配一律拒绝。
 */
@Data
public class CloudContextProperties {

    /** 出站传播：调用下游时带上租户/身份/链路头 */
    private boolean propagate = true;

    /** 入站恢复过滤器是否注册（注册 ≠ 恢复身份，身份另看 restoreLogin） */
    private boolean filterEnabled = true;

    /** 是否允许从请求头恢复租户上下文（需同时通过内部调用校验） */
    private boolean restoreTenant = false;

    /** 是否允许从请求头恢复登录态（需同时通过内部调用校验） */
    private boolean restoreLogin = false;

    /**
     * 内部调用凭证：非空时，入站请求必须携带 {@code X-PivotOS-Internal: <本值>}
     * 才允许恢复租户/登录态。<b>留空 = 拒绝一切恢复</b>（含 restoreTenant 已开启的情形）。
     */
    private String internalToken = "";

    /** 恢复登录态时使用的账号体系（无法从传播头推断时的默认值） */
    private String defaultAccountType = "sys-user";
}
