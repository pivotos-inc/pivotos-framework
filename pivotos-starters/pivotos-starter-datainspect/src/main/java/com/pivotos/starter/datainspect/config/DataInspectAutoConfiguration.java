package com.pivotos.starter.datainspect.config;

import com.pivotos.starter.datainspect.api.config.DataInspectProperties;
import com.pivotos.starter.datainspect.api.route.DataInspectorFactory;
import com.pivotos.starter.datainspect.api.security.SqlGuard;
import com.pivotos.starter.datainspect.api.spi.DataSourceInspector;
import com.pivotos.starter.datainspect.mysql.MySqlInspector;
import com.pivotos.starter.datainspect.security.MySqlSqlGuard;
import com.pivotos.starter.tenant.config.properties.TenantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 数据监控自动装配。
 *
 * <p>纪律（S117 经验）：<b>不用 {@code @ConditionalOnBean} 判断实现是否就绪</b>——
 * 跨自动配置的 Bean 条件存在时序窗口；组件可用性一律由运行期 {@code isAvailable()} 表达。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "pivotos.datainspect", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties
public class DataInspectAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DataInspectAutoConfiguration.class);

    /** 配置绑定（bean 方法级 @ConfigurationProperties：契约包保持零 Spring 依赖） */
    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "pivotos.datainspect")
    public DataInspectProperties dataInspectProperties() {
        return new DataInspectProperties();
    }

    /** SQL 安全闸门：硬生效，与权限无关 */
    @Bean
    @ConditionalOnMissingBean
    public SqlGuard mySqlSqlGuard(DataInspectProperties properties) {
        return new MySqlSqlGuard(properties.getMaxSqlLength());
    }

    /**
     * MySQL 组件实现（只读）。
     *
     * <p>租户忽略表清单取自 {@link TenantProperties#BUILTIN_IGNORE_TABLES}——唯一来源，不复制一份常量，
     * 避免与 MyBatis 租户拦截器漂移。
     */
    @Bean
    @ConditionalOnMissingBean
    public MySqlInspector mySqlInspector(DataSource dataSource, DataInspectProperties properties, SqlGuard guard,
                                         ObjectProvider<TenantProperties> tenantProperties) {
        TenantProperties tenant = tenantProperties.getIfAvailable();
        Supplier<Set<String>> ignoreTables = tenant == null
                ? () -> TenantProperties.BUILTIN_IGNORE_TABLES
                : tenant::effectiveIgnoreTables;
        log.info("[PivotOS][datainspect] MySQL 组件已装配（自由查询 {}，表白名单 {}）",
                properties.isQueryEnabled() ? "开启" : "关闭",
                properties.getMysql().isAllowAllTables() ? "关闭（任意表）" : "开启");
        return new MySqlInspector(dataSource, properties, guard, ignoreTables);
    }

    /** 组件路由工厂：收集全部 DataSourceInspector Bean，按 type 索引 */
    @Bean
    @ConditionalOnMissingBean
    public DataInspectorFactory dataInspectorFactory(List<DataSourceInspector> inspectors) {
        return new DataInspectorFactory(inspectors);
    }
}
