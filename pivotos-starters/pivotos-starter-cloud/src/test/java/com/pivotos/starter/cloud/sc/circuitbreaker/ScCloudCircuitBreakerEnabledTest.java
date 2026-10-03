package com.pivotos.starter.cloud.sc.circuitbreaker;

import com.pivotos.starter.cloud.sc.config.ScCloudAutoConfiguration;
import com.pivotos.starter.cloud.sc.config.ScCloudCircuitBreakerAutoConfiguration;
import com.pivotos.starter.cloud.sc.config.ScCloudProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断开关打开时的装配测试：证明 {@code pivotos.cloud.sc.circuit-breaker.*}
 * 真的灌进了 Resilience4j 配置，而不只是「Bean 在、配置没接上」。
 */
@SpringBootTest(
    classes = ScCloudCircuitBreakerEnabledTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.provider=cloud",
        "pivotos.cloud.sc.circuit-breaker.enabled=true",
        "pivotos.cloud.sc.circuit-breaker.failure-rate-threshold=77",
        "pivotos.cloud.sc.circuit-breaker.minimum-number-of-calls=9",
        "pivotos.cloud.sc.circuit-breaker.sliding-window-size=8",
        "pivotos.cloud.sc.circuit-breaker.fallback-mode=local"
    }
)
class ScCloudCircuitBreakerEnabledTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 支撑Bean全部就位() {
        assertThat(applicationContext.getBeanNamesForType(CloudCircuitBreakerSupport.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(CloudCircuitBreakerStats.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(CircuitBreakerRegistry.class))
            .as("注册表必须存在（容器已有则复用，否则自建）").isNotEmpty();
    }

    @Test
    void 属性真的灌进了Resilience4j配置() {
        ScCloudProperties scProps = applicationContext.getBean(ScCloudProperties.class);
        // 这一条是「BFPP 提前实例化导致配置静默失效」的防回归断言：
        // 只要有人把 properties Bean 重新注入回 FeignFacadeRegistrar，这里会立刻变红。
        assertThat(scProps.getCircuitBreaker().isEnabled()).as("嵌套属性必须被绑定到").isTrue();
        assertThat(scProps.getCircuitBreaker().getFailureRateThreshold()).isEqualTo(77);

        CloudCircuitBreakerSupport support = applicationContext.getBean(CloudCircuitBreakerSupport.class);
        assertThat(support.config().getFailureRateThreshold()).isEqualTo(77f);
        assertThat(support.config().getMinimumNumberOfCalls()).isEqualTo(9);
        assertThat(support.config().getSlidingWindowSize()).isEqualTo(8);
        assertThat(support.properties().fallbackMode()).isEqualTo(FallbackMode.LOCAL);
    }

    /**
     * 刻意用 {@code @ImportAutoConfiguration} 而不是 {@code @EnableAutoConfiguration}：
     * 本模块没有 servlet 依赖，后者会把 api 包的 {@code CloudContextAutoConfiguration}
     * （依赖 jakarta.servlet.Filter）一起拉起来，直接 NoClassDefFoundError。
     * 这里只导入被测装配 + 属性绑定，范围可控。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({com.pivotos.starter.cloud.api.config.CloudProperties.class,
        com.pivotos.starter.cloud.sc.config.ScCloudProperties.class})
    @ImportAutoConfiguration({
        ConfigurationPropertiesAutoConfiguration.class,
        ScCloudAutoConfiguration.class,
        ScCloudCircuitBreakerAutoConfiguration.class
    })
    static class TestApp {
    }
}
