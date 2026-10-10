package com.pivotos.starter.cloud.api.context;

import com.pivotos.common.core.constant.CommonConstants;

/**
 * 跨进程上下文传播的请求头常量。
 *
 * <p>三个通道（local / cloud / alibaba）与 Gateway 共用同一套头名，
 * 保证「A 通道的调用方 → B 通道的服务方」也能对上（通道是部署形态，不是协议）。
 *
 * <p>链路 ID 刻意复用 {@link CommonConstants#TRACE_ID_HEADER}：既有的
 * {@code TraceIdFilter} 已经会「有则继承、无则生成」，跨进程 trace 由此天然延续，
 * 不需要本包再插一脚。
 */
public final class CloudHeaders {

    /** 私有头统一前缀 */
    public static final String PREFIX = "X-PivotOS-";

    /** 租户 ID */
    public static final String TENANT_ID = PREFIX + "Tenant-Id";

    /** 登录用户 ID */
    public static final String USER_ID = PREFIX + "User-Id";

    /** 登录用户名 */
    public static final String USERNAME = PREFIX + "Username";

    /** 账号体系（sys-user / app-user / wx-mini-user） */
    public static final String ACCOUNT_TYPE = PREFIX + "Account-Type";

    /** 链路 ID：复用既有常量，值与 {@link CommonConstants#TRACE_ID_HEADER} 相同 */
    public static final String TRACE_ID = CommonConstants.TRACE_ID_HEADER;

    /**
     * 内部调用凭证。
     *
     * <p><b>为什么必须有它</b>：租户与登录态一旦可以「凭请求头恢复」，
     * 任何能直达服务端口的调用方都能伪造 {@code X-PivotOS-User-Id} 变成任意用户——
     * 这是比「没做传播」严重得多的漏洞。故恢复 tenant / login 必须同时校验本凭证，
     * 且凭证未配置（空串）时一律拒绝恢复。
     */
    public static final String INTERNAL_TOKEN = PREFIX + "Internal";

    private CloudHeaders() {
    }
}
