package com.pivotos.starter.cloud.sc.circuitbreaker;

import com.pivotos.starter.cloud.sc.config.ScCloudAutoConfiguration;
import com.pivotos.starter.cloud.sc.config.ScCloudCircuitBreakerAutoConfiguration;
import com.pivotos.starter.cloud.sc.fixture.ProbedFacade;
import com.pivotos.starter.cloud.sc.fixture.ProbedLocalFacade;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 默认形态（熔断开关未打开）的取证：Feign 客户端<b>一层都没多包</b>。
 *
 * <p>与 {@link FeignFacadeCircuitBreakerTest} 成对，一开一关才能把「默认不劣化」钉死：
 * 光证明「开了会拦」不够，还得证明「没开就完全没碰」。
 */
@SpringBootTest(
    classes = FeignFacadeNoCircuitBreakerTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.provider=cloud",
        "pivotos.cloud.sc.proxied=com.pivotos.starter.cloud.sc.fixture.ProbedFacade",
        "pivotos.cloud.sc.url=http://127.0.0.1:1"
        // 刻意不开 pivotos.cloud.sc.circuit-breaker.enabled
    }
)
class FeignFacadeNoCircuitBreakerTest {

    @Autowired
    private ProbedFacade probedFacade;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void feign客户端未被熔断包装() {
        assertThat(Proxy.isProxyClass(probedFacade.getClass())).as("替换成 Feign 客户端这件事仍然要发生").isTrue();
        assertThat(Proxy.getInvocationHandler(probedFacade).getClass().getName())
            .as("开关未打开时必须是 Feign 自己的 InvocationHandler，多包一层都算劣化")
            .doesNotContain("CircuitBreakerFacadeProxy");
    }

    @Test
    void 容器里没有熔断相关Bean() {
        assertThat(applicationContext.getBeanNamesForType(CloudCircuitBreakerSupport.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(CloudCircuitBreakerStats.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(CircuitBreakerRegistry.class)).isEmpty();
    }

    @Test
    void 连续失败不会触发熔断拒绝() {
        for (int i = 0; i < 5; i++) {
            String value = "x" + i;
            assertThatThrownBy(() -> probedFacade.echo(value))
                .as("未开熔断时每次都是真实的调用失败，不会被就地拒绝")
                .isNotInstanceOf(CloudCircuitBreakerOpenException.class);
        }
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

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        static org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor probedLocalFacade() {
            return registry -> registry.registerBeanDefinition("probedFacade",
                new org.springframework.beans.factory.support.RootBeanDefinition(ProbedLocalFacade.class));
        }
    }
}
