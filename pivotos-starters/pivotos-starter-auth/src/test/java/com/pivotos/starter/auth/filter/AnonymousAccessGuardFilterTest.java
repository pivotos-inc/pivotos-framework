package com.pivotos.starter.auth.filter;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.starter.core.context.LoginContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匿名访问闸门（v3.1.0 生产化 · A4）的负向断言。
 *
 * <p>覆盖四类处置里「闸门」这一支的判定分支，每条都是负向：
 * 未登录必须拿不到数据（链路不继续）、已登录必须放行、非守卫前缀必须不受影响、
 * 例外前缀与 OPTIONS 预检必须放行。
 *
 * <p><b>为什么用 ScopedValue 手动绑定而不是起 Spring 容器</b>：
 * {@code LoginContext} 是 ScopedValue 门面，绑定成本就是一次 {@code ScopedValue.where(...).run(...)}，
 * 起容器会让这组 8 条断言从毫秒级变成分钟级，且拿不到「链路到底有没有继续」这样的确定性信号。
 */
class AnonymousAccessGuardFilterTest {

    private static final List<String> WARM_FLOW = List.of("/warm-flow/**");

    /** 一次调用的观测结果：链路是否继续 + 响应状态与响应体 */
    private record Outcome(boolean chainContinued, int status, String body) {
    }

    private Outcome invoke(List<String> paths, List<String> excludes, String uri,
                           String contextPath, String method, boolean login) {
        AnonymousAccessGuardFilter filter = new AnonymousAccessGuardFilter(paths, excludes, "该端点要求登录后访问");
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (contextPath != null) {
            request.setContextPath(contextPath);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] continued = {false};
        FilterChain chain = (req, res) -> continued[0] = true;
        Runnable task = () -> {
            try {
                filter.doFilter(request, response, chain);
            } catch (ServletException | IOException e) {
                throw new IllegalStateException(e);
            }
        };
        if (login) {
            ScopedValue.where(LoginContext.KEY, new LoginUser(1001L, "alice", "sys-user", 12L)).run(task);
        } else {
            task.run();
        }
        String body;
        try {
            body = response.getContentAsString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new Outcome(continued[0], response.getStatus(), body);
    }

    @Test
    void 未登录命中守卫前缀_链路不继续且返回未登录码() {
        Outcome out = invoke(WARM_FLOW, List.of(), "/warm-flow/query-def", null, "GET", false);
        assertFalse(out.chainContinued(), "匿名命中守卫前缀时不得继续往下走（否则等于没拦）");
        assertEquals(200, out.status(), "鉴权失败沿用既有形态：HTTP 200 + 业务码（与 @SaCheckLogin 一致，不是 401）");
        // 码值不写字面量：与枚举对齐，枚举改了这里跟着红，避免两处漂移
        assertTrue(out.body().contains("\"code\":" + GlobalErrorCode.UNAUTHORIZED.getCode()),
                "响应体必须带未登录码，实际=" + out.body());
        assertTrue(out.body().contains("\"success\":false"), "响应体必须显式 success=false，实际=" + out.body());
        assertFalse(out.body().contains("jdbc:") && out.body().contains("password"),
                "拒绝响应不得带任何连接信息");
    }

    @Test
    void 已登录命中守卫前缀_放行() {
        Outcome out = invoke(WARM_FLOW, List.of(), "/warm-flow/query-def", null, "GET", true);
        assertTrue(out.chainContinued(), "已登录必须放行，否则新版设计器的保存/回读会整条断掉");
        assertEquals("", out.body(), "放行时不得写任何响应体");
    }

    @Test
    void 未登录但不在守卫前缀内_必须完全不受影响() {
        Outcome out = invoke(WARM_FLOW, List.of(), "/system/user/page", null, "GET", false);
        assertTrue(out.chainContinued(), "闸门只守登记前缀；自有端点的鉴权仍归注解体系，不能被误伤");
    }

    @Test
    void 守卫前缀不得顺带吃掉旧设计器入口() {
        // /warm-flow-ui/** 由 LegacyDesignerGuardFilter 管（410 + code=4100），
        // Ant 语义下 /warm-flow/** 不应匹配 /warm-flow-ui/xxx——这里钉死，防止有人把前缀改成 /warm-flow*
        Outcome out = invoke(WARM_FLOW, List.of(), "/warm-flow-ui/index.html", null, "GET", false);
        assertTrue(out.chainContinued(), "/warm-flow/** 不得匹配 /warm-flow-ui/**（两者语义与响应码都不同）");
    }

    @Test
    void 例外前缀优先于守卫前缀() {
        Outcome out = invoke(WARM_FLOW, List.of("/warm-flow/query-def"), "/warm-flow/query-def", null, "GET", false);
        assertTrue(out.chainContinued(), "登记在例外里的端点必须放行（个别第三方端点确需匿名时的唯一出口）");
    }

    @Test
    void OPTIONS预检一律放行() {
        Outcome out = invoke(WARM_FLOW, List.of(), "/warm-flow/save-json", null, "OPTIONS", false);
        assertTrue(out.chainContinued(), "CORS 预检不带 Authorization，拦了会让跨域调用直接死");
    }

    @Test
    void 部署在非根路径时前缀仍然命中() {
        Outcome out = invoke(WARM_FLOW, List.of(), "/api/warm-flow/query-def", "/api", "GET", false);
        assertFalse(out.chainContinued(), "必须剥掉 context-path 再匹配，否则换部署路径守卫静默失效");
    }

    @Test
    void 守卫前缀为空时一律放行() {
        Outcome out = invoke(List.of(), List.of(), "/warm-flow/query-def", null, "GET", false);
        assertTrue(out.chainContinued(), "未配置任何前缀时不得拦截任何请求（配置缺失 ≠ 全站封死）");
    }

    @Test
    void POST写端点同样被守() {
        // 只守 GET 是常见偷懒做法：warm-flow 的 save-json 是 POST，漏了等于没守
        Outcome out = invoke(WARM_FLOW, List.of(), "/warm-flow/save-json", null, "POST", false);
        assertFalse(out.chainContinued(), "守卫必须覆盖全部 HTTP 方法，不只是 GET");
    }
}
