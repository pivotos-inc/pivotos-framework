package com.pivotos.starter.cloud.sc.circuitbreaker;

import com.pivotos.starter.cloud.sc.config.ScCloudAutoConfiguration;
import com.pivotos.starter.cloud.sc.config.ScCloudCircuitBreakerAutoConfiguration;
import com.pivotos.starter.cloud.sc.fixture.ProbedFacade;
import com.pivotos.starter.cloud.sc.fixture.ProbedLocalFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 Feign 链路上的熔断取证：不是 mock 出来的调用，而是真去连一个不可达端口。
 *
 * <p>取证链条：Feign 客户端 → 熔断代理 → 真实连接失败 → 状态机打开 → 后续调用被就地拒绝。
 * 这一步的意义在于把「状态机测试」与「接线测试」合起来：
 * 前面证明了熔断会拦，这里证明<b>拦的位置正是 Feign 调用链上</b>。
 *
 * <p>下游用 {@code http://127.0.0.1:1}：端口保留且无监听，连接会立刻被拒（不会挂住），
 * 也不需要任何外部中间件，可进默认回归集。
 */
@SpringBootTest(
    classes = FeignFacadeCircuitBreakerTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.provider=cloud",
        "pivotos.cloud.sc.proxied=com.pivotos.starter.cloud.sc.fixture.ProbedFacade",
        "pivotos.cloud.sc.url=http://127.0.0.1:1",
        "pivotos.cloud.sc.circuit-breaker.enabled=true",
        "pivotos.cloud.sc.circuit-breaker.sliding-window-size=2",
        "pivotos.cloud.sc.circuit-breaker.minimum-number-of-calls=2",
        "pivotos.cloud.sc.circuit-breaker.failure-rate-threshold=50",
        // OPEN 停留 10 分钟：让本用例在同一个上下文内稳定断言「已打开」
        "pivotos.cloud.sc.circuit-breaker.wait-duration-in-open-state=600000ms"
    }
)
class FeignFacadeCircuitBreakerTest {

    @Autowired
    private ProbedFacade probedFacade;

    @Autowired
    private CloudCircuitBreakerStats stats;

    @Test
    void 真实Feign调用被熔断拦下() {
        assertThat(Proxy.isProxyClass(probedFacade.getClass())).isTrue();
        assertThat(Proxy.getInvocationHandler(probedFacade).getClass().getName())
            .as("Facade Bean 必须被换成「Feign 客户端 + 熔断代理」，本地实现只剩 $Local")
            .contains("CircuitBreakerFacadeProxy");

        // 前两次：真的发出 HTTP 请求，连 127.0.0.1:1 失败
        assertThatThrownBy(() -> probedFacade.echo("a")).isInstanceOf(Exception.class)
            .isNotInstanceOf(CloudCircuitBreakerOpenException.class);
        assertThatThrownBy(() -> probedFacade.echo("b")).isInstanceOf(Exception.class)
            .isNotInstanceOf(CloudCircuitBreakerOpenException.class);

        CloudCircuitBreakerStats.Snapshot beforeReject = stats.snapshot("pivotosCloud.ProbedFacade");
        assertThat(beforeReject.calls()).isEqualTo(2);
        assertThat(beforeReject.failures()).isEqualTo(2);
        assertThat(beforeReject.notPermitted()).isZero();

        // 第三次：熔断已开，请求根本发不出去
        assertThatThrownBy(() -> probedFacade.echo("c"))
            .isInstanceOf(CloudCircuitBreakerOpenException.class)
            .hasMessageContaining("pivotosCloud.ProbedFacade");

        CloudCircuitBreakerStats.Snapshot afterReject = stats.snapshot("pivotosCloud.ProbedFacade");
        assertThat(afterReject.notPermitted()).as("被拒绝的调用必须被计数（这是「没打出去」的硬证据）").isEqualTo(1);
        assertThat(afterReject.failures()).as("被拒绝的调用不计入失败（它根本没发生）").isEqualTo(2);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(com.pivotos.starter.cloud.api.config.CloudProperties.class)
    @ImportAutoConfiguration({
        ConfigurationPropertiesAutoConfiguration.class,
        org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration.class,
        FeignAutoConfiguration.class,
        ScCloudAutoConfiguration.class,
        ScCloudCircuitBreakerAutoConfiguration.class
    })
    static class TestApp {

        /**
         * 刻意用 BeanDefinition 而不是 {@code @Bean} 方法注册本地实现：
         * {@code FacadeProxySupport.findLocalBeanName} 靠 {@code beanClassName} 找实现，
         * 而 {@code @Bean} 工厂方法定义的 {@code beanClassName} 是 null（会被跳过）。
         * 真实项目里本地实现是 {@code @Service} 扫描进来的，形态与这里一致。
         */
        @Bean
        @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        static org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor probedLocalFacade() {
            return registry -> registry.registerBeanDefinition("probedFacade",
                new org.springframework.beans.factory.support.RootBeanDefinition(ProbedLocalFacade.class));
        }
    }
}
