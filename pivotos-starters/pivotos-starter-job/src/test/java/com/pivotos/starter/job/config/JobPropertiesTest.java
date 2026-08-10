package com.pivotos.starter.job.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for JobProperties configuration binding.
 */
@DisplayName("JobProperties tests")
class JobPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JobPropertiesConfig.class);

    @Test
    @DisplayName("should bind default values")
    void shouldBindDefaultValues() {
        contextRunner.run(ctx -> {
            JobProperties props = ctx.getBean(JobProperties.class);
            assertThat(props.getAdminAddresses()).isNull();
            assertThat(props.getAppName()).isEqualTo("pivotos-executor");
            assertThat(props.getPort()).isEqualTo(9999);
            assertThat(props.getLogRetentionDays()).isEqualTo(30);
            assertThat(props.getLogPath()).isEqualTo("./logs/xxl-job");
        });
    }

    @Test
    @DisplayName("should bind custom values")
    void shouldBindCustomValues() {
        contextRunner
                .withPropertyValues(
                        "pivotos.job.admin-addresses=http://192.168.1.100:9080/xxl-job-admin",
                        "pivotos.job.app-name=custom-app",
                        "pivotos.job.access-token=custom-token-123",
                        "pivotos.job.port=9998",
                        "pivotos.job.log-path=/var/log/xxl-job",
                        "pivotos.job.log-retention-days=60"
                )
                .run(ctx -> {
                    JobProperties props = ctx.getBean(JobProperties.class);
                    assertThat(props.getAdminAddresses()).isEqualTo("http://192.168.1.100:9080/xxl-job-admin");
                    assertThat(props.getAppName()).isEqualTo("custom-app");
                    assertThat(props.getAccessToken()).isEqualTo("custom-token-123");
                    assertThat(props.getPort()).isEqualTo(9998);
                    assertThat(props.getLogPath()).isEqualTo("/var/log/xxl-job");
                    assertThat(props.getLogRetentionDays()).isEqualTo(60);
                });
    }

    @Test
    @DisplayName("should bind app-name property")
    void shouldBindAppName() {
        contextRunner
                .withPropertyValues("pivotos.job.app-name=test-executor")
                .run(ctx -> {
                    JobProperties props = ctx.getBean(JobProperties.class);
                    assertThat(props.getAppName()).isEqualTo("test-executor");
                });
    }

    @EnableConfigurationProperties(JobProperties.class)
    static class JobPropertiesConfig {
    }
}
