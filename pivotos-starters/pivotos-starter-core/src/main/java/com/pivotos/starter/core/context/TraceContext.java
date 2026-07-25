package com.pivotos.starter.core.context;

/**
 * 链路追踪上下文静态门面（ScopedValue 实现）。
 * 由 TraceIdFilter 在请求入口绑定。
 */
public final class TraceContext {

    /** 上下文键，绑定操作只允许 TraceIdFilter 使用 */
    public static final ScopedValue<String> KEY = ScopedValue.newInstance();

    private TraceContext() {
    }

    /**
     * 当前链路 ID，未绑定返回 null
     */
    public static String get() {
        return KEY.isBound() ? KEY.get() : null;
    }
}
