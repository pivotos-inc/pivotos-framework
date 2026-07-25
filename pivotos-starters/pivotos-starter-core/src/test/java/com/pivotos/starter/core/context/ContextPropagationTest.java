package com.pivotos.starter.core.context;

import com.pivotos.common.api.context.LoginUser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文绑定 / 读取 / 异步传递单测（含虚拟线程场景，S5 验收卡点）
 */
class ContextPropagationTest {

    @Test
    void bindAndRead() {
        assertNull(LoginContext.get());
        LoginUser user = new LoginUser(1L, "admin", "sys-user", null);
        ScopedValue.where(LoginContext.KEY, user).run(() -> {
            assertTrue(LoginContext.isLogin());
            assertEquals(1L, LoginContext.getUserId());
            assertEquals("admin", LoginContext.getUsername());
        });
        // 作用域外自动解绑
        assertNull(LoginContext.get());
        assertFalse(LoginContext.isLogin());
    }

    @Test
    void propagateToVirtualThread() throws Exception {
        LoginUser user = new LoginUser(7L, "tester", "sys-user", 99L);
        ScopedValue.where(LoginContext.KEY, user)
            .where(TenantContext.KEY, 99L)
            .where(TraceContext.KEY, "trace-abc")
            .call(() -> {
                try (ExecutorService executor = ContextExecutor.wrap(Executors.newVirtualThreadPerTaskExecutor())) {
                    Future<?> future = executor.submit(() -> {
                        assertEquals(7L, LoginContext.getUserId());
                        assertEquals(99L, TenantContext.get());
                        assertEquals("trace-abc", TraceContext.get());
                    });
                    future.get(10, TimeUnit.SECONDS);
                }
                return null;
            });
    }

    @Test
    void propagateWithPartialContext() throws Exception {
        // 只绑定 TraceId，登录/租户维度未绑定：异步线程中应读到 null 而不是抛异常
        ScopedValue.where(TraceContext.KEY, "trace-only").call(() -> {
            try (ExecutorService executor = ContextExecutor.wrap(Executors.newVirtualThreadPerTaskExecutor())) {
                Future<?> future = executor.submit(() -> {
                    assertEquals("trace-only", TraceContext.get());
                    assertNull(LoginContext.get());
                    assertNull(TenantContext.get());
                });
                future.get(10, TimeUnit.SECONDS);
            }
            return null;
        });
    }

    @Test
    void propagateToPlatformThreadPool() throws Exception {
        // 平台线程池（非虚拟线程）场景同样要透传
        LoginUser user = new LoginUser(8L, "pool-user", "app-user", null);
        ScopedValue.where(LoginContext.KEY, user).call(() -> {
            try (ExecutorService executor = ContextExecutor.wrap(Executors.newFixedThreadPool(2))) {
                Future<String> future = executor.submit(LoginContext::getUsername);
                assertEquals("pool-user", future.get(10, TimeUnit.SECONDS));
            }
            return null;
        });
    }

    @Test
    void facadeReadsContext() {
        ScopedValueContextFacade facade = new ScopedValueContextFacade();
        LoginUser user = new LoginUser(1L, "admin", "sys-user", 5L);
        ScopedValue.where(LoginContext.KEY, user)
                .where(TraceContext.KEY, "t-1")
                .run(() -> {
                    assertEquals(1L, facade.getLoginUserId());
                    assertEquals("admin", facade.getLoginUsername());
                    assertEquals(5L, facade.getTenantId());
                    assertEquals("t-1", facade.getTraceId());
                });
    }
}
