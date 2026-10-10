package com.pivotos.starter.cloud.api.support;

import com.pivotos.starter.cloud.api.support.fixture.ProbeFacade;
import com.pivotos.starter.cloud.api.support.fixture.ProbeLocalFacade;
import com.pivotos.starter.cloud.api.support.fixture.ProbeProxyFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Facade 反射定位语义：local 通道 Servlet 与 facade-http 端点共用这套语义，这里钉死它。
 *
 * <p><b>要防的是哪种事故</b>：「优先本地实现」写成「按类型取 Bean」后，在没有发生远程替换时
 * 看着也正常，一旦本地实现被代理顶掉就成了自调用死循环（代理 → HTTP → 本进程代理 → …），
 * 而编译期与常规单测全绿。
 */
class FacadeInvocationSupportTest {

    private GenericApplicationContext contextWithBoth() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(ProbeProxyFacade.BEAN_NAME, ProbeProxyFacade.class);
        context.registerBean(ProbeLocalFacade.BEAN_NAME, ProbeLocalFacade.class);
        context.refresh();
        return context;
    }

    @Test
    @DisplayName("$Local 存在时必须命中本地实现，绝不能再次命中远程代理")
    void prefers_local_alias_over_proxy() {
        try (GenericApplicationContext context = contextWithBoth()) {
            Object target = FacadeInvocationSupport.resolveTarget(context, ProbeFacade.class);
            assertThat(target).isInstanceOf(ProbeLocalFacade.class);
        }
    }

    @Test
    @DisplayName("没有替换发生时（无 $Local 别名）按类型取到本地实现")
    void falls_back_to_plain_bean_when_no_replacement() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ProbeLocalFacade.BEAN_NAME, ProbeLocalFacade.class);
            context.refresh();
            assertThat(FacadeInvocationSupport.resolveTarget(context, ProbeFacade.class))
                .isInstanceOf(ProbeLocalFacade.class);
        }
    }

    @Test
    @DisplayName("本进程没有该 Facade 实现时返回 null（由调用方决定 404 还是别的码）")
    void returns_null_when_no_implementation() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            assertThat(FacadeInvocationSupport.resolveTarget(context, ProbeFacade.class)).isNull();
        }
    }

    @Test
    @DisplayName("按「方法名 + 元数」定位：同名重载取元数匹配的那一个")
    void resolves_method_by_arity() {
        Method oneArg = FacadeInvocationSupport.resolveMethod(ProbeFacade.class, "greet", 1);
        Method twoArgs = FacadeInvocationSupport.resolveMethod(ProbeFacade.class, "greet", 2);

        assertThat(oneArg).isNotNull();
        assertThat(twoArgs).isNotNull();
        assertThat(oneArg.getParameterCount()).isEqualTo(1);
        assertThat(twoArgs.getParameterCount()).isEqualTo(2);

        Method noArgs = FacadeInvocationSupport.resolveMethod(ProbeFacade.class, "ping", 0);
        assertThat(noArgs).isNotNull();
        assertThat(noArgs.getName()).isEqualTo("ping");
    }

    @Test
    @DisplayName("元数不匹配时找不到方法（返回 null，拒绝调用）")
    void returns_null_when_arity_mismatch() {
        assertThat(FacadeInvocationSupport.resolveMethod(ProbeFacade.class, "greet", 3)).isNull();
        assertThat(FacadeInvocationSupport.resolveMethod(ProbeFacade.class, "notExist", 1)).isNull();
    }
}
