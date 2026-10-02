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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * <p>Redis / ES 组件的类是 <b>optional 依赖</b>，因此走「嵌套 {@code @Configuration} +
 * {@code @ConditionalOnClass(name=...)}」——<b>用字符串形态而非类字面量</b>，
 * 这样在依赖缺席时连嵌套类的字节码都不会被加载（类字面量会把类型引用写进主类的注解里）。
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

    /**
     * Redis 组件（只读：SCAN + TYPE 感知取值；<b>无 QUERY 能力</b>）。
     *
     * <p>库号取自 {@code spring.data.redis.database}——Redisson 的 {@code Config#getSingleServerConfig()}
     * 是 protected 拿不到，而它与 redisson-spring-boot-starter 同源，是最准的来源。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    public static class RedisInspectorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public com.pivotos.starter.datainspect.redis.RedisInspector redisInspector(
                ObjectProvider<org.redisson.api.RedissonClient> redissonClient,
                DataInspectProperties properties,
                ObjectProvider<org.springframework.core.env.Environment> environment) {
            int database = 0;
            org.springframework.core.env.Environment env = environment.getIfAvailable();
            if (env != null) {
                try {
                    database = Integer.parseInt(String.valueOf(env.getProperty("spring.data.redis.database", "0")));
                } catch (NumberFormatException e) {
                    database = 0;
                }
            }
            log.info("[PivotOS][datainspect] Redis 组件已装配（db{}，key 上限 {}，SCAN 轮次上限 {}，"
                            + "value 截断 {} 字符，无 QUERY 能力）",
                    database, properties.getRedis().getMaxKeys(), properties.getRedis().getMaxScanIterations(),
                    properties.getRedis().getValueTruncateBytes());
            return new com.pivotos.starter.datainspect.redis.RedisInspector(
                    redissonClient.getIfAvailable(), properties, database);
        }
    }

    /**
     * ES 组件（只读：{@code _cat/indices} + {@code _search}；<b>无 QUERY 能力</b>）。
     *
     * <p>只在 {@code pivotos.search.type=es-java} 时才会有 {@code ElasticsearchClient} Bean
     * （{@code EsJavaSearchAutoConfiguration} 带 {@code @ConditionalOnProperty}），
     * 因此 type=simple 时这里拿到 null → 组件降级并在 UI 明示原因。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "co.elastic.clients.elasticsearch.ElasticsearchClient")
    public static class EsInspectorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public com.pivotos.starter.datainspect.es.EsDataInspector esDataInspector(
                ObjectProvider<co.elastic.clients.elasticsearch.ElasticsearchClient> client,
                ObjectProvider<com.pivotos.starter.search.api.config.SearchProperties> searchProperties,
                ObjectProvider<com.pivotos.starter.search.esjava.EsJavaSearchProvider> provider,
                DataInspectProperties properties) {
            log.info("[PivotOS][datainspect] ES 组件已装配（单页文档上限 {}，索引清单上限 {}，无 QUERY 能力）",
                    properties.getEs().getMaxRows(), properties.getEs().getMaxItems());
            return new com.pivotos.starter.datainspect.es.EsDataInspector(
                    client.getIfAvailable(), searchProperties.getIfAvailable(),
                    provider.getIfAvailable(), properties);
        }
    }
}
