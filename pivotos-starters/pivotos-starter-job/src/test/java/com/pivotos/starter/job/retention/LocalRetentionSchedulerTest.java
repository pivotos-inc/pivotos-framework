package com.pivotos.starter.job.retention;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地调度兜底的装配守卫（v3.1.0 P1-B1）。
 * <p>
 * 本类守的是<strong>「不双跑」</strong>这条硬口径：XXL-Job 调度中心已装配时，
 * 本地 {@code @Scheduled} 兜底必须整个不注册，否则同一次清理会被跑两遍
 * （两遍都按主键删、结果幂等，但会翻倍占用数据库连接并让执行记录变乱）。
 * 这条若是靠「记得改配置」来维持，迟早会破，故用 {@code @ConditionalOnMissingBean} +
 * 本测试钉死。
 * <p>
 * 另一个易错点一并守：{@link RetentionJobRegistry} <strong>无条件可用</strong>——
 * 一旦它跟着 {@code pivotos.retention.enabled} 一起消失，各插件 Job 的构造注入会失败，
 * 「关功能」会变成「应用起不来」。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@DisplayName("LocalRetentionScheduler wiring")
class LocalRetentionSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RetentionAutoConfiguration.class, LocalRetentionScheduler.class));

    /**
     * 用 Mockito 造 XxlJobSpringExecutor：
     * 真实实例是 SmartInitializingSingleton，start() 会真的去连调度中心并监听 9999 端口，
     * 单测里绝不能实例化它；mock 只提供类型（供 @ConditionalOnMissingBean 判定），生命周期方法是空实现。
     */
    @Configuration(proxyBeanMethods = false)
    static class XxlExecutorPresentConfig {

        @Bean
        XxlJobSpringExecutor xxlJobSpringExecutor() {
            return Mockito.mock(XxlJobSpringExecutor.class);
        }
    }

    @Test
    @DisplayName("S1 无 XXL-Job 执行器时：本地兜底生效（这是「调度中心不可达」的形态）")
    void s1_localFallbackActiveWithoutXxlJob() {
        contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(LocalRetentionScheduler.class));
    }

    @Test
    @DisplayName("S2 已有 XXL-Job 执行器时：本地兜底整个不注册，杜绝双跑")
    void s2_localFallbackAbsentWhenXxlJobPresent() {
        contextRunner.withUserConfiguration(XxlExecutorPresentConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(XxlJobSpringExecutor.class);
                    assertThat(ctx).doesNotHaveBean(LocalRetentionScheduler.class);
                });
    }

    @Test
    @DisplayName("S3 local-fallback.enabled=false 时：显式关掉本地兜底")
    void s3_canBeDisabledExplicitly() {
        contextRunner.withPropertyValues("pivotos.retention.local-fallback.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LocalRetentionScheduler.class));
    }

    @Test
    @DisplayName("S4 关掉留存总开关：注册表仍可用，且不影响兜底装配（开关只作用于执行期）")
    void s4_registryAlwaysPresent() {
        contextRunner.withPropertyValues("pivotos.retention.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RetentionJobRegistry.class);
                    assertThat(ctx).hasSingleBean(LocalRetentionScheduler.class)
                            .as("总开关只决定「执行期是否跳过」，不该让 Bean 消失——否则插件注入即失败");
                });
    }

    @Test
    @DisplayName("S6 force=true：即使调度中心已装配也强制接管（中心配置了但不可达的补救档）")
    void s6_forceOverridesXxlJobPresence() {
        contextRunner
                .withUserConfiguration(XxlExecutorPresentConfig.class)
                .withPropertyValues("pivotos.retention.local-fallback.force=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(LocalRetentionScheduler.class));
    }

    @Test
    @DisplayName("S7 force 默认关闭：不能因为加了开关就改变既有行为")
    void s7_forceDefaultsToFalse() {
        contextRunner.withUserConfiguration(XxlExecutorPresentConfig.class)
                .run(ctx -> {
                    assertThat(ctx.getBean(RetentionProperties.class).getLocalFallback().isForce()).isFalse();
                    assertThat(ctx).doesNotHaveBean(LocalRetentionScheduler.class);
                });
    }

    @Test
    @DisplayName("S5 已登记任务确实能被本地兜底遍历到（注册表接线闭环）")
    void s5_registryIsUsableByScheduler() {
        contextRunner.run(ctx -> {
            RetentionJobRegistry registry = ctx.getBean(RetentionJobRegistry.class);
            assertThat(registry.size()).isZero();
            registry.register("demoCleanup", "演示清理", () -> {
            });
            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.list()).extracting("handler").containsExactly("demoCleanup");
            assertThat(registry.find("demoCleanup")).isPresent();
            assertThat(registry.find("notExist")).isEmpty();
        });
    }
}
