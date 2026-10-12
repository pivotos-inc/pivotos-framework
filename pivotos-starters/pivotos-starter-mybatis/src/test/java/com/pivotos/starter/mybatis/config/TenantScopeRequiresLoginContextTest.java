package com.pivotos.starter.mybatis.config;

import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.mybatis.config.properties.MybatisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「登录 ⇒ 租户过滤才生效」这条因果链的守卫（v3.1.0 生产化 · A4）。
 *
 * <p><b>为什么这条测试属于 A4</b>：实证 {@code GET /migration/task/page} 匿名可翻全租户任务，
 * 表面看是「端点没加鉴权」，真正致命的是第二层——<b>匿名请求没有租户上下文</b>，
 * 而默认 {@code TenantLineHandler#ignoreTable} 在 {@code TenantContext.get() == null} 时
 * 返回 true（全表放行）。也就是说：不登录时 MP 的租户拦截器<b>根本不工作</b>，
 * 加上鉴权后租户隔离才自动补上。这是「加 @SaCheckLogin」在本例中能一并解决
 * 「无租户」问题的原因，值得单独钉一条测试防止后人改坏。
 *
 * <p>反向提醒：不要把「无上下文」改成「按 0 过滤」，那会让单租户模式（dev 即是）
 * 直接查不到数据，属于静默级破坏。
 */
class TenantScopeRequiresLoginContextTest {

    private final MybatisProperties properties = new MybatisProperties();
    private final StandardEnvironment environment = new StandardEnvironment();

    @Test
    void 无租户上下文时租户过滤整体放行() {
        var handler = new MybatisPlusAutoConfiguration(properties, environment).tenantLineHandler(properties);
        assertTrue(handler.ignoreTable("migration_task"),
                "无租户上下文（= 匿名请求）时租户过滤放行——这正是「匿名可翻全租户」的根因，口径不可改");
        assertEquals("tenant_id", handler.getTenantIdColumn());
    }

    @Test
    void 有租户上下文时才按值过滤() {
        var handler = new MybatisPlusAutoConfiguration(properties, environment).tenantLineHandler(properties);
        ScopedValue.where(TenantContext.KEY, 12L).run(() -> {
            assertFalse(handler.ignoreTable("migration_task"),
                    "登录并绑定租户后必须按值过滤，否则等于跨租户可读");
            assertEquals("12", handler.getTenantId().toString(),
                    "过滤值必须取自 TenantContext，而不是写死的 0");
        });
    }
}
