package com.pivotos.starter.tenant.it;

import com.pivotos.starter.tenant.config.TenantAutoConfiguration;
import com.pivotos.starter.tenant.context.TenantContextFilter;
import com.pivotos.starter.tenant.resolver.TenantResolver;
import com.pivotos.starter.tenant.strategy.ColumnTenantStrategy;
import com.pivotos.starter.tenant.strategy.DatasourceTenantStrategy;
import com.pivotos.starter.tenant.strategy.SchemaTenantStrategy;
import com.pivotos.starter.tenant.strategy.TenantStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条件装配测试（纯上下文，不起库）：
 * 默认/显式关闭 → 容器内 0 个 tenant Bean（默认不启用时全量测试不受影响的根基）；
 * 启用后按 mode 三选一装配策略。
 */
class TenantDisabledAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantAutoConfiguration.class));

    @Test
    void disabled_byDefault_noTenantBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(TenantStrategy.class);
            assertThat(context).doesNotHaveBean(TenantResolver.class);
            assertThat(context).doesNotHaveBean(TenantContextFilter.class);
        });
    }

    @Test
    void disabled_explicitly_noTenantBeans() {
        runner.withPropertyValues("pivotos.tenant.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TenantStrategy.class));
    }

    @Test
    void enabled_column_defaultMode() {
        runner.withPropertyValues("pivotos.tenant.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantStrategy.class);
                    assertThat(context.getBean(TenantStrategy.class)).isInstanceOf(ColumnTenantStrategy.class);
                    assertThat(context).hasSingleBean(TenantResolver.class);
                });
    }

    @Test
    void enabled_schema_strategy() {
        runner.withPropertyValues("pivotos.tenant.enabled=true", "pivotos.tenant.mode=schema")
                .run(context -> assertThat(context.getBean(TenantStrategy.class))
                        .isInstanceOf(SchemaTenantStrategy.class));
    }

    @Test
    void enabled_datasource_strategy() {
        runner.withPropertyValues("pivotos.tenant.enabled=true", "pivotos.tenant.mode=datasource")
                .run(context -> assertThat(context.getBean(TenantStrategy.class))
                        .isInstanceOf(DatasourceTenantStrategy.class));
    }
}
