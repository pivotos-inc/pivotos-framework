package com.pivotos.ai.client;

import com.pivotos.ai.domain.entity.AiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型池轮询器测试（V3.2-D）。
 *
 * <p>锁死的风险：百炼免费额度<b>按模型独立发放且官方不会自动切换</b>，
 * 「轮询」与「耗尽摘除」这两件事只要有一件静默失效，表现都是
 * 「服务照常工作、钱照常花」——不会报错，肉眼完全看不出来。所以必须断言到位。
 *
 * <p>负向自检口径：把 {@code isFreeTierExhausted} 改成只查顶层、把 {@code nextModel}
 * 改成不跳过耗尽项、把全耗尽降级去掉，对应断言必须变红（见收口报告）。
 */
class AiModelPoolRouterTest {

    private static final String FREE_TIER = "AllocationQuota.FreeTierOnly";

    private static AiProvider provider(Long id, String defaultModel, String pool) {
        AiProvider p = new AiProvider();
        p.setId(id);
        p.setCode("dashscope");
        p.setDefaultModel(defaultModel);
        p.setModelPool(pool);
        return p;
    }

    /* ==================== 模型池解析 ==================== */

    @Test
    @DisplayName("P-1 池为空/null/全分隔符 → 解析为空列表（行为退化为只用默认模型）")
    void parsePoolEmpty() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        assertTrue(r.parsePool(provider(1L, "d", null)).isEmpty());
        assertTrue(r.parsePool(provider(1L, "d", "")).isEmpty());
        assertTrue(r.parsePool(provider(1L, "d", "   ")).isEmpty());
        assertTrue(r.parsePool(provider(1L, "d", ", ,")).isEmpty());
    }

    @Test
    @DisplayName("P-2 池解析：trim + 去空 + 去重 + 保序")
    void parsePoolNormalize() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        assertEquals(List.of("a", "b", "c"), r.parsePool(provider(1L, "d", " a , b ,c ")));
        assertEquals(List.of("a", "b"), r.parsePool(provider(1L, "d", "a,,b,")));
        assertEquals(List.of("a", "b"), r.parsePool(provider(1L, "d", "a,b,a")));
    }

    /* ==================== 轮询 ==================== */

    @Test
    @DisplayName("P-3 未配池 → 恒返回默认模型（原有行为不变，零回归风险）")
    void noPoolFallsBackToDefault() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "qwen3.8-flash", null);
        assertEquals("qwen3.8-flash", r.nextModel(p));
        assertEquals("qwen3.8-flash", r.nextModel(p));
        assertEquals("qwen3.8-flash", r.nextModel(p));
    }

    @Test
    @DisplayName("P-4 三次调用依次轮到池内三个模型，第四次回到起点（跨请求分摊而非请求内打转）")
    void roundRobinAcrossCalls() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "d", "m1,m2,m3");
        assertEquals("m1", r.nextModel(p));
        assertEquals("m2", r.nextModel(p));
        assertEquals("m3", r.nextModel(p));
        assertEquals("m1", r.nextModel(p));
    }

    @Test
    @DisplayName("P-5 不同供应商各自独立游标，互不影响")
    void cursorIsPerProvider() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p1 = provider(1L, "d1", "a1,a2");
        AiProvider p2 = provider(2L, "d2", "b1,b2");
        assertEquals("a1", r.nextModel(p1));
        assertEquals("b1", r.nextModel(p2));
        assertEquals("a2", r.nextModel(p1));
        assertEquals("b2", r.nextModel(p2));
    }

    /* ==================== 耗尽摘除 ==================== */

    @Test
    @DisplayName("P-6 标记耗尽后跳过该模型，改投池内下一个")
    void skipExhausted() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "d", "m1,m2,m3");
        r.markExhausted(1L, "m1");
        assertEquals("m2", r.nextModel(p));
        assertEquals("m3", r.nextModel(p));
        // m1 仍耗尽 → 跳过它回到 m2
        assertEquals("m2", r.nextModel(p));
    }

    @Test
    @DisplayName("P-7 池内全部耗尽 → 降级到默认模型（保证服务不因免费额度用尽而整体不可用）")
    void allExhaustedDegradesToDefault() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "fallback", "m1,m2");
        r.markExhausted(1L, "m1");
        r.markExhausted(1L, "m2");
        assertEquals("fallback", r.nextModel(p));
        assertEquals("fallback", r.nextModel(p));
    }

    @Test
    @DisplayName("P-8 耗尽标记带 TTL，到期自动恢复（防一次误判把模型永久摘掉）")
    void exhaustedMarkExpires() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "d", "m1,m2");
        // 测试接缝：1ms TTL
        r.markExhausted(1L, "m1", 1L);
        assertTrue(r.isExhausted(1L, "m1"));
        assertEquals("m2", r.nextModel(p));
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(r.isExhausted(1L, "m1"), "TTL 过期后应自动恢复可用");
    }

    @Test
    @DisplayName("P-9 markExhausted 对 null/空模型名是安全的空操作（不产生脏 key）")
    void markExhaustedNullSafe() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        r.markExhausted(null, "m1");
        r.markExhausted(1L, null);
        r.markExhausted(1L, "  ");
        assertFalse(r.isExhausted(1L, "m1"));
    }

    @Test
    @DisplayName("P-10 evictProvider 清空该供应商的游标与耗尽标记")
    void evictProvider() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "d", "m1,m2");
        r.markExhausted(1L, "m1");
        r.nextModel(p);
        r.evictProvider(1L);
        assertFalse(r.isExhausted(1L, "m1"));
        assertEquals("m1", r.nextModel(p), "游标重置后应回到池首");
    }

    /* ==================== peek / resolve ==================== */

    @Test
    @DisplayName("P-11 peekModel 不推进游标：查询动作本身不该改变「下一个用谁」")
    void peekDoesNotAdvance() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "d", "m1,m2");
        assertEquals("m1", r.peekModel(p));
        assertEquals("m1", r.peekModel(p));
        assertEquals("m1", r.peekModel(p));
        assertEquals("m1", r.nextModel(p), "peek 三次后首个 next 仍应是 m1");
        assertEquals("m2", r.nextModel(p));
    }

    @Test
    @DisplayName("P-12 请求显式指定模型时不轮询（尊重调用方选择）")
    void explicitModelWins() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "d", "m1,m2");
        assertEquals("qwen-max", r.resolveModel(p, " qwen-max "));
        assertEquals("qwen-max", r.resolveModel(p, "qwen-max"));
        // 显式指定不推进游标
        assertEquals("m1", r.nextModel(p));
    }

    @Test
    @DisplayName("P-13 请求未指定（null/空白）→ 走池轮询")
    void blankModelUsesPool() {
        AiModelPoolRouter r = new AiModelPoolRouter();
        AiProvider p = provider(1L, "d", "m1,m2");
        assertEquals("m1", r.resolveModel(p, null));
        assertEquals("m2", r.resolveModel(p, "   "));
    }

    /* ==================== 免费额度耗尽识别 ==================== */

    @Test
    @DisplayName("P-14 顶层异常消息含错误码 → 识别为耗尽")
    void detectTopLevel() {
        assertTrue(AiModelPoolRouter.isFreeTierExhausted(new RuntimeException("HTTP 403 " + FREE_TIER)));
    }

    @Test
    @DisplayName("P-15 错误码埋在深层 cause 里也要识别（Spring AI 会包好几层，只查顶层会漏判）")
    void detectNestedCause() {
        Throwable root = new IllegalStateException(" AllocationQuota.FreeTierOnly ");
        Throwable mid = new RuntimeException("调用失败", root);
        Throwable top = new RuntimeException("ChatClient 调用异常", mid);
        assertTrue(AiModelPoolRouter.isFreeTierExhausted(top));
    }

    @Test
    @DisplayName("P-16 普通异常 / null / 无 message 均判为「非耗尽」")
    void detectNegative() {
        assertFalse(AiModelPoolRouter.isFreeTierExhausted(null));
        assertFalse(AiModelPoolRouter.isFreeTierExhausted(new RuntimeException("连接超时")));
        assertFalse(AiModelPoolRouter.isFreeTierExhausted(new RuntimeException("403 Forbidden")));
        assertFalse(AiModelPoolRouter.isFreeTierExhausted(new RuntimeException((String) null)));
    }

    @Test
    @DisplayName("P-17 cause 自引用成环时不能死循环（防御性上限生效）")
    void detectSelfReferencingCause() {
        Throwable t = mock(Throwable.class);
        when(t.getMessage()).thenReturn("boom");
        when(t.getCause()).thenReturn(t);
        assertFalse(AiModelPoolRouter.isFreeTierExhausted(t));
    }

    @Test
    @DisplayName("P-18 错误码常量本身与官方口径一致（改错字符串等于整个机制失效）")
    void errorCodeConstant() {
        assertEquals("AllocationQuota.FreeTierOnly", AiModelPoolRouter.FREE_TIER_EXHAUSTED_CODE);
    }
}
