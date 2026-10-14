package com.pivotos.starter.tracing.web;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MdcContextFilter} 的行为断言（零外部资源，用 {@code ScopedValue} 真实绑定上下文）。
 *
 * <p>两条最有价值的断言：
 * <ol>
 *   <li><b>身份会进 MDC</b> —— 排查多租户问题时「哪个用户 / 哪个租户」必须能直接从日志读出来；</li>
 *   <li><b>请求结束必清干净</b> —— 容器线程复用，残留会把上一个请求的身份带给下一个请求（串号）。</li>
 * </ol>
 *
 * <p>取样窗口只能在过滤器<b>内部</b>：一旦走出过滤器，MDC 已被 finally 清掉，
 * 在外面断言「有值」永远是红的（这是写这类测试最容易踩的假红）。
 */
class MdcContextFilterTest {

    private final MdcContextFilter filter = new MdcContextFilter();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void identity_is_visible_inside_request_and_cleared_right_after() throws Exception {
        LoginUser user = new LoginUser(1001L, "alice", "sys", 12L);
        CapturingChain chain = new CapturingChain();

        ScopedValue.where(LoginContext.KEY, user)
            .where(TenantContext.KEY, 12L)
            .run(() -> {
                try {
                    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                chain.markAfterLeave();
            });

        assertThat(chain.userId).as("userId 必须在请求处理期间可见").isEqualTo("1001");
        assertThat(chain.username).isEqualTo("alice");
        assertThat(chain.tenantId).isEqualTo("12");
        assertThat(chain.clearedAfterChain).as("链路走完后必须已清理，否则线程复用会串号").isTrue();
    }

    @Test
    void anonymous_request_writes_no_identity_keys() throws Exception {
        CapturingChain chain = new CapturingChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.userId).isNull();
        assertThat(chain.username).isNull();
        assertThat(chain.tenantId).isNull();
    }

    @Test
    void tenant_is_absent_when_context_unbound() throws Exception {
        LoginUser user = new LoginUser(1001L, "alice", "sys", null);
        CapturingChain chain = new CapturingChain();

        ScopedValue.where(LoginContext.KEY, user).run(() -> {
            try {
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            chain.markAfterLeave();
        });

        assertThat(chain.userId).isEqualTo("1001");
        assertThat(chain.tenantId).as("未绑定租户上下文时不应凭空写 tenantId").isNull();
    }

    /** 在请求链内部取样 MDC（唯一取样窗口），并在过滤器返回后复查是否已清理 */
    private static class CapturingChain implements FilterChain {

        private String userId;
        private String username;
        private String tenantId;
        private boolean clearedAfterChain;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            userId = MDC.get(MdcContextFilter.KEY_USER_ID);
            username = MDC.get(MdcContextFilter.KEY_USERNAME);
            tenantId = MDC.get(MdcContextFilter.KEY_TENANT_ID);
        }

        /** 由测试在 filter.doFilter 返回后显式调用，避免依赖 MockFilterChain 的时序 */
        void markAfterLeave() {
            clearedAfterChain = MDC.get(MdcContextFilter.KEY_USER_ID) == null
                && MDC.get(MdcContextFilter.KEY_USERNAME) == null
                && MDC.get(MdcContextFilter.KEY_TENANT_ID) == null;
        }
    }
}
