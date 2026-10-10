package com.pivotos.starter.cloud.sc.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * Feign 客户端外层的熔断代理。
 *
 * <p>包在 Feign 客户端<b>外面</b>而不是改 Feign 内部：这样「开关关闭」时
 * {@code getObject()} 返回的就是 Feign 客户端本体（可 {@code assertSame} 证明零包装），
 * 不需要任何 Feign 侧配合，也不用碰 {@code spring.cloud.openfeign.circuitbreaker.enabled}
 * （那个开关是全局的、且 fallback 必须是个 Class，拿不到容器里的本地实现）。
 *
 * <p>口径三条：
 * <ol>
 *   <li><b>拒绝即抛</b>：OPEN / HALF_OPEN 超限时抛 {@link CloudCircuitBreakerOpenException}，
 *       带熔断名与状态，绝不返回 null 默认值（那会把「下游挂了」伪装成「业务没数据」）；</li>
 *   <li><b>失败不包装</b>：单次调用失败原样抛出原始异常（调用方既有 catch 逻辑不受影响），只计数；</li>
 *   <li><b>降级可显式</b>：{@code fallback-mode=local} 时落到进程内实现，本地实现缺席则照抛异常。</li>
 * </ol>
 */
public final class CircuitBreakerFacadeProxy implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerFacadeProxy.class);

    private final Class<?> iface;
    private final Object target;
    private final CloudCircuitBreakerSupport support;
    private final Supplier<Object> localFallback;

    private CircuitBreakerFacadeProxy(Class<?> iface,
                                      Object target,
                                      CloudCircuitBreakerSupport support,
                                      Supplier<Object> localFallback) {
        this.iface = iface;
        this.target = target;
        this.support = support;
        this.localFallback = localFallback;
    }

    /** 给 Feign 客户端套一层熔断代理 */
    public static Object wrap(Class<?> iface,
                              Object target,
                              CloudCircuitBreakerSupport support,
                              Supplier<Object> localFallback) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
            new CircuitBreakerFacadeProxy(iface, target, support, localFallback));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Object 方法（toString/equals/hashCode）不进熔断：它们不是远程调用，
        // 计入窗口只会污染失败率，而且 toString 被日志框架随手一调就可能记一次失败。
        if (method.getDeclaringClass() == Object.class) {
            return invokeOn(target, method, args);
        }

        String name = support.nameOf(iface, method);
        CircuitBreaker circuitBreaker = support.circuitBreaker(name);
        support.stats().recordCall(name);

        CheckedSupplier<Object> guarded = CircuitBreaker.decorateCheckedSupplier(circuitBreaker,
            () -> invokeOn(target, method, args));
        try {
            Object result = guarded.get();
            support.stats().recordSuccess(name);
            return result;
        } catch (CallNotPermittedException e) {
            support.stats().recordNotPermitted(name);
            return onRejected(name, circuitBreaker, method, args);
        } catch (Throwable t) {
            // 原样抛出：调用方需要看到真实的失败原因，包装一层只会让排障多绕一圈
            support.stats().recordFailure(name);
            throw t;
        }
    }

    private Object onRejected(String name, CircuitBreaker circuitBreaker, Method method, Object[] args) throws Throwable {
        String targetDesc = iface.getSimpleName() + "#" + method.getName();
        if (support.properties().fallbackMode() == FallbackMode.LOCAL) {
            Object local = localFallback == null ? null : localFallback.get();
            if (local != null) {
                support.stats().recordFallback(name);
                log.warn("[CLOUD][cloud][CB] {} 已回落本地实现：{}（state={}）",
                    name, targetDesc, circuitBreaker.getState());
                try {
                    return invokeOn(local, method, args);
                } catch (Throwable t) {
                    // 降级实现自己也失败了：如实抛，只额外记一笔（便于区分「降级没救回来」）
                    support.stats().recordFallbackFailure(name);
                    throw t;
                }
            }
            // 本地实现缺席：绝不静默返回 null，按 none 口径抛出去
            log.warn("[CLOUD][cloud][CB] {} 降级失败：容器内找不到 {} 的本地实现，按抛异常口径处理",
                name, iface.getName());
        }
        throw new CloudCircuitBreakerOpenException(name, circuitBreaker.getState(), targetDesc,
            circuitBreaker.getMetrics().getFailureRate());
    }

    private static Object invokeOn(Object instance, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }
}
