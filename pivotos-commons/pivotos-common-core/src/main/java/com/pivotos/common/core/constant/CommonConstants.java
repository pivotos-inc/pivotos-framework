package com.pivotos.common.core.constant;

/**
 * 全局常量（不做任何框架引用）
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    /** 链路追踪 ID 请求头 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 登录用户 ID 请求头（网关/内部透传用） */
    public static final String LOGIN_USER_HEADER = "X-Login-User";

    /** 租户 ID 请求头 */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /** 默认超管用户 ID */
    public static final Long SUPER_ADMIN_ID = 1L;

    /** 树根节点 ID */
    public static final Long TREE_ROOT_ID = 0L;
}
