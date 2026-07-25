package com.pivotos.starter.core.context;

/**
 * 租户上下文静态门面（ScopedValue 实现）。
 * 单租户模式下全程不绑定，读取返回 null。
 */
public final class TenantContext {

    /** 上下文键，绑定操作只允许 tenant Starter 使用 */
    public static final ScopedValue<Long> KEY = ScopedValue.newInstance();

    private TenantContext() {
    }

    /**
     * 当前租户 ID，无租户上下文返回 null
     */
    public static Long get() {
        return KEY.isBound() ? KEY.get() : null;
    }

    /**
     * 是否存在租户上下文
     */
    public static boolean isBound() {
        return KEY.isBound();
    }
}
