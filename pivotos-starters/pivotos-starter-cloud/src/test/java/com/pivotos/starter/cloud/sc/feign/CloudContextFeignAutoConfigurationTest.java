package com.pivotos.starter.cloud.sc.feign;

import com.pivotos.starter.cloud.api.CloudProvider;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.sc.config.ScCloudAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站拦截器装配形态取证：cloud 与 alibaba 都要装，local 与总开关关闭时都不装。
 *
 * <p><b>为什么单独钉死这一条</b>：L5 实测发现 alibaba 形态（{@code -P alibaba} 时
 * provider=alibaba）下原装配条件 {@code @ConditionalOnCloudProvider(CLOUD)} 不成立，
 * Feign 出站<b>一个身份头都不带</b>——而 Nacos 真机 IT 里 Feign 是能调通的，
 * 「调用成功」掩盖了「身份没传」，属典型的「看着生效、实际没生效」。
 *
 * <p>刻意用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：
 * 四个形态各起一个容器太重，且本用例只关心装配判定，不关心运行环境。
 */
class CloudContextFeignAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class,
            CloudContextFeignAutoConfiguration.class));

    @Test
    void cloud形态装配出站拦截器() {
        runner.withPropertyValues("pivotos.cloud.provider=cloud")
            .run(context -> {
                assertThat(context).hasSingleBean(CloudContextFeignInterceptor.class);
                assertThat(context).hasSingleBean(CloudProperties.class);
            });
    }

    @Test
    void alibaba形态同样装配出站拦截器() {
        runner.withPropertyValues("pivotos.cloud.provider=alibaba")
            .run(context -> assertThat(context).hasSingleBean(CloudContextFeignInterceptor.class));
    }

    @Test
    void local形态不装配Feign拦截器() {
        runner.withPropertyValues("pivotos.cloud.provider=local")
            .run(context -> assertThat(context).doesNotHaveBean(CloudContextFeignInterceptor.class));
    }

    @Test
    void 通道配错回落local时不装配() {
        runner.withPropertyValues("pivotos.cloud.provider=不存在的通道")
            .run(context -> assertThat(context).doesNotHaveBean(CloudContextFeignInterceptor.class));
    }

    @Test
    void 总开关关闭时不装配() {
        runner.withPropertyValues("pivotos.cloud.enabled=false", "pivotos.cloud.provider=cloud")
            .run(context -> assertThat(context).doesNotHaveBean(CloudContextFeignInterceptor.class));
    }

    /**
     * 反向取证：SC 通道自动配置本身<b>没有</b>被放宽到 alibaba ——
     * 否则 {@link ScCloudAutoConfiguration} 会在 Nacos 形态下装配出
     * {@code ScServiceInstanceProvider}，与 alibaba 自己的实例提供者抢 Bean。
     */
    @Test
    void alibaba形态不装配SC通道专属Bean() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                ScCloudAutoConfiguration.class,
                CloudContextFeignAutoConfiguration.class))
            .withPropertyValues("pivotos.cloud.provider=alibaba")
            .run(context -> {
                assertThat(context).doesNotHaveBean(com.pivotos.starter.cloud.sc.discovery.ScServiceInstanceProvider.class);
                assertThat(context).hasSingleBean(CloudContextFeignInterceptor.class);
            });
    }

    @Test
    void 多值条件注解不破坏单值用法() {
        // CloudProviderCondition 现按数组判定，既有单值写法（CLOUD / ALIBABA / LOCAL）必须仍然成立
        assertThat(CloudProvider.CLOUD).isNotNull();
        assertThat(CloudProvider.of("alibaba")).isEqualTo(CloudProvider.ALIBABA);
        assertThat(CloudProvider.of(null)).isEqualTo(CloudProvider.LOCAL);
    }
}
