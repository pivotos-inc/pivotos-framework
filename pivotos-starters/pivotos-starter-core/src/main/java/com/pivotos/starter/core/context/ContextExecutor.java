package com.pivotos.starter.core.context;

import com.pivotos.common.api.context.LoginUser;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 上下文感知执行器：捕获提交时的 Login/Tenant/Trace 上下文，
 * 在异步线程中重绑定，替代 ThreadLocal 方案（红线：禁用裸线程/裸异步）。
 * <p>用法：{@code ContextExecutor.wrap(Executors.newVirtualThreadPerTaskExecutor())}
 * 或直接注入容器中的 contextExecutor Bean。
 */
public final class ContextExecutor {

    private ContextExecutor() {
    }

    /**
     * 包装普通 Executor
     */
    public static Executor wrap(Executor executor) {
        return task -> executor.execute(capture(task));
    }

    /**
     * 包装 ExecutorService（保留 shutdown/close 等生命周期能力）
     */
    public static ExecutorService wrap(ExecutorService executor) {
        return new ContextExecutorService(executor);
    }

    /**
     * 捕获当前上下文，返回可在任意线程重放上下文的 Runnable
     */
    public static Runnable capture(Runnable task) {
        LoginUser user = LoginContext.get();
        Long tenantId = TenantContext.get();
        String traceId = TraceContext.get();
        return () -> runWithAll(user, tenantId, traceId, task);
    }

    private static void runWithAll(LoginUser user, Long tenantId, String traceId, Runnable task) {
        runWith(LoginContext.KEY, user,
                () -> runWith(TenantContext.KEY, tenantId,
                        () -> runWith(TraceContext.KEY, traceId, task)));
    }

    /** ScopedValue 不允许绑定 null，未绑定的维度直接跳过 */
    private static <T> void runWith(ScopedValue<T> key, T value, Runnable next) {
        if (value == null) {
            next.run();
        } else {
            ScopedValue.where(key, value).run(next);
        }
    }
}
