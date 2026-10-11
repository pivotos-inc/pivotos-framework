package com.pivotos.starter.job.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 留存配置绑定与默认值测试（v3.1.0 P1-B1）。
 * <p>
 * 默认值本身是产品决策，必须有测试钉住：把 {@code min-retention-days} 改成 0 等于
 * 拆掉「保留窗口硬下限」这道安全带；把某个表的默认天数改小等于静默缩短留存。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@DisplayName("RetentionProperties")
class RetentionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RetentionPropertiesConfig.class);

    @EnableConfigurationProperties(RetentionProperties.class)
    static class RetentionPropertiesConfig {
    }

    @Test
    @DisplayName("should bind safe defaults")
    void shouldBindSafeDefaults() {
        contextRunner.run(ctx -> {
            RetentionProperties props = ctx.getBean(RetentionProperties.class);

            assertThat(props.isEnabled()).as("留存策略默认生效，否则等于没做").isTrue();
            assertThat(props.isDryRun()).as("默认不是演练模式").isFalse();
            assertThat(props.getMaxDeletePerRun()).isEqualTo(5000);
            assertThat(props.getMinRetentionDays()).as("硬下限必须 > 0").isEqualTo(1);

            assertThat(props.getLoginLogDays()).isEqualTo(180);
            assertThat(props.getJobLogDays()).isEqualTo(180);
            assertThat(props.getOperLogDays()).isEqualTo(180);
            assertThat(props.getMessageDays()).isEqualTo(180);
            assertThat(props.getAiChatDays()).isEqualTo(180);
            assertThat(props.getAiToolInvokeDays()).isEqualTo(180);
            assertThat(props.getAiUsageDays()).as("用量是计费依据，默认比会话保留更久").isEqualTo(365);
            assertThat(props.getFlowHisTaskDays()).as("流程历史是审批留痕，默认保留最久").isEqualTo(365);

            assertThat(props.getLocalFallback().isEnabled()).isTrue();
            assertThat(props.getLocalFallback().getCron()).isEqualTo("0 0 3 * * *");
        });
    }

    @Test
    @DisplayName("should resolve PIVOTOS_RETENTION_* env var names to config keys（文档写法背书）")
    void shouldResolveEnvironmentVariableNames() {
        // docsite《数据留存与清理》给出的运维覆盖方式就是这一组下划线大写变量。
        // 若映射不成立，运维照文档配了会「设了不生效且完全不报错」——本项目吃过这种亏
        // （PIVOTOS_MYBATIS_FIELD_ENCRYPT_KEY_GUARD_ENABLED 就曾被文档写漏一段）。
        // 这里直接断言 Spring 官方环境变量→配置键的映射规则，端到端生效性由
        // scripts/e2e/retention_cleanup_test.py 起服注入真实环境变量验证。
        var env = new org.springframework.core.env.SystemEnvironmentPropertySource(
                "systemEnvironment",
                java.util.Map.of(
                        "PIVOTOS_RETENTION_DRY_RUN", "true",
                        "PIVOTOS_RETENTION_MAX_DELETE_PER_RUN", "100",
                        "PIVOTOS_RETENTION_FLOW_HIS_TASK_DAYS", "1095",
                        "PIVOTOS_RETENTION_LOCAL_FALLBACK_CRON", "0 30 4 * * *"));

        assertThat(env.getProperty("pivotos.retention.dry-run")).isEqualTo("true");
        assertThat(env.getProperty("pivotos.retention.max-delete-per-run")).isEqualTo("100");
        assertThat(env.getProperty("pivotos.retention.flow-his-task-days")).isEqualTo("1095");
        assertThat(env.getProperty("pivotos.retention.local-fallback.cron")).isEqualTo("0 30 4 * * *");
    }

    @Test
    @DisplayName("should bind overrides including nested local-fallback")
    void shouldBindOverrides() {
        contextRunner
                .withPropertyValues(
                        "pivotos.retention.enabled=false",
                        "pivotos.retention.dry-run=true",
                        "pivotos.retention.max-delete-per-run=100",
                        "pivotos.retention.min-retention-days=30",
                        "pivotos.retention.login-log-days=7",
                        "pivotos.retention.flow-his-task-days=1095",
                        "pivotos.retention.local-fallback.enabled=false",
                        "pivotos.retention.local-fallback.cron=0 30 4 * * *")
                .run(ctx -> {
                    RetentionProperties props = ctx.getBean(RetentionProperties.class);

                    assertThat(props.isEnabled()).isFalse();
                    assertThat(props.isDryRun()).isTrue();
                    assertThat(props.getMaxDeletePerRun()).isEqualTo(100);
                    assertThat(props.getMinRetentionDays()).isEqualTo(30);
                    assertThat(props.getLoginLogDays()).isEqualTo(7);
                    assertThat(props.getFlowHisTaskDays()).isEqualTo(1095);
                    assertThat(props.getLocalFallback().isEnabled()).isFalse();
                    assertThat(props.getLocalFallback().getCron()).isEqualTo("0 30 4 * * *");
                });
    }
}
