package com.pivotos.starter.core.context;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 上下文透传的 ExecutorService 装饰器：提交任务时捕获上下文，
 * 生命周期方法全部委托底层执行器。
 */
public class ContextExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    public ContextExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(ContextExecutor.capture(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        LoginUserSnapshot snapshot = LoginUserSnapshot.capture();
        return delegate.submit(() -> snapshot.call(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(ContextExecutor.capture(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(ContextExecutor.capture(task));
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private <T> Collection<? extends Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        LoginUserSnapshot snapshot = LoginUserSnapshot.capture();
        return tasks.stream().<Callable<T>>map(task -> () -> snapshot.call(task)).toList();
    }

    /** Callable 场景的上下文快照（Callable 可返回值，单独处理） */
    record LoginUserSnapshot(com.pivotos.common.api.context.LoginUser user, Long tenantId, String traceId) {

        static LoginUserSnapshot capture() {
            return new LoginUserSnapshot(LoginContext.get(), TenantContext.get(), TraceContext.get());
        }

        <T> T call(Callable<T> task) throws Exception {
            LoginUserSnapshot self = this;
            final Object[] box = new Object[1];
            final Exception[] error = new Exception[1];
            ContextExecutorLike.runAll(self.user, self.tenantId, self.traceId, () -> {
                try {
                    box[0] = task.call();
                } catch (Exception e) {
                    error[0] = e;
                }
            });
            if (error[0] != null) {
                throw error[0];
            }
            @SuppressWarnings("unchecked")
            T result = (T) box[0];
            return result;
        }
    }

    /** 与 ContextExecutor 相同的逐维度绑定逻辑（供 Callable 包装复用） */
    static final class ContextExecutorLike {

        static void runAll(com.pivotos.common.api.context.LoginUser user, Long tenantId, String traceId, Runnable task) {
            runWith(LoginContext.KEY, user,
                    () -> runWith(TenantContext.KEY, tenantId,
                            () -> runWith(TraceContext.KEY, traceId, task)));
        }

        private static <T> void runWith(ScopedValue<T> key, T value, Runnable next) {
            if (value == null) {
                next.run();
            } else {
                ScopedValue.where(key, value).run(next);
            }
        }
    }
}
