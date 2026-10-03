package com.pivotos.starter.cloud.sc.circuitbreaker;

import com.pivotos.starter.cloud.sc.config.ScCloudAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.pivotos.starter.cloud.sc.config.ScCloudCircuitBreakerAutoConfiguration;
import com.pivotos.starter.cloud.sc.fixture.EchoFacade;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断开关关闭时的装配测试（默认形态）。
 *
 * <p>取证目标是「引入 ≠ 生效」的反向一半：<b>没打开就等于没装配</b>。
 * 断言的是「容器里查不到这些 Bean」而不是「Bean 在但内部跳过」——
 * 后者只能靠读代码相信，前者能被测试钉死。
 */
@SpringBootTest(
    classes = ScCloudCircuitBreakerDisabledTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.provider=cloud"
        // 刻意不配 pivotos.cloud.sc.circuit-breaker.enabled：默认 false
    }
)
class ScCloudCircuitBreakerDisabledTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 默认不装配任何熔断支撑Bean() {
        assertThat(applicationContext.getBeanNamesForType(CloudCircuitBreakerSupport.class))
            .as("开关未打开时熔断门面必须不存在").isEmpty();
        assertThat(applicationContext.getBeanNamesForType(CloudCircuitBreakerStats.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(CircuitBreakerRegistry.class))
            .as("连注册表都不该被建出来").isEmpty();
    }

    @Test
    void 未配proxied时Facade保持本地实现() {
        assertThat(applicationContext.getBean(EchoFacade.class))
            .as("proxied 为空 = 不替换任何 Bean，本地实现照旧")
            .isInstanceOf(EchoLocalFacade.class);
    }

    /** 同 Enabled 用例：只导入被测装配，避免拉起需要 servlet 的 api 自动配置 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(com.pivotos.starter.cloud.api.config.CloudProperties.class)
    @ImportAutoConfiguration({
        ConfigurationPropertiesAutoConfiguration.class,
        ScCloudAutoConfiguration.class,
        ScCloudCircuitBreakerAutoConfiguration.class
    })
    static class TestApp {

        @Bean
        EchoFacade echoFacade() {
            return new EchoLocalFacade();
        }
    }

    /** 本地实现替身：断言「没被换成 Feign」用得上 */
    static class EchoLocalFacade implements EchoFacade {

        @Override
        public String echo(String value) {
            return "local:" + value;
        }

        @Override
        public void ping() {
            // no-op
        }
    }
}
