package com.pivotos.starter.job.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for JobAutoConfiguration.
 */
@DisplayName("JobAutoConfiguration tests")
class JobAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JobAutoConfiguration.class));

    @Test
    @DisplayName("should create XxlJobSpringExecutor bean when admin-addresses is set")
    void shouldCreateExecutorBeanWhenAdminAddressesSet() {
        contextRunner
                .withPropertyValues(
                        "pivotos.job.admin-addresses=http://127.0.0.1:9080/xxl-job-admin",
                        "pivotos.job.app-name=test-executor",
                        "pivotos.job.port=9999"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(XxlJobSpringExecutor.class);
                    XxlJobSpringExecutor executor = ctx.getBean(XxlJobSpringExecutor.class);
                    assertThat(executor).isNotNull();
                });
    }

    @Test
    @DisplayName("should NOT create executor when admin-addresses is not set")
    void shouldNotCreateExecutorWhenAdminAddressesNotSet() {
        contextRunner
                .withPropertyValues(
                        "pivotos.job.app-name=test-executor"
                )
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(XxlJobSpringExecutor.class);
                });
    }

    @Test
    @DisplayName("should expose jobLogRetentionDays bean")
    void shouldExposeRetentionDaysBean() {
        contextRunner
                .withPropertyValues(
                        "pivotos.job.admin-addresses=http://127.0.0.1:9080/xxl-job-admin",
                        "pivotos.job.log-retention-days=90"
                )
                .run(ctx -> {
                    Integer days = ctx.getBean("jobLogRetentionDays", Integer.class);
                    assertThat(days).isEqualTo(90);
                });
    }
}
