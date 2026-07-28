package com.pivotos.starter.tenant.config;

import com.pivotos.starter.mybatis.config.MybatisPlusAutoConfiguration;
import com.pivotos.starter.mybatis.config.properties.MybatisProperties;
import com.pivotos.starter.tenant.config.properties.TenantProperties;
import com.pivotos.starter.tenant.context.TenantContextFilter;
import com.pivotos.starter.tenant.resolver.DefaultTenantResolver;
import com.pivotos.starter.tenant.resolver.TenantResolver;
import com.pivotos.starter.tenant.strategy.ColumnTenantStrategy;
import com.pivotos.starter.tenant.strategy.DatasourceTenantStrategy;
import com.pivotos.starter.tenant.strategy.SchemaTenantStrategy;
import com.pivotos.starter.tenant.strategy.TenantStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 多租户自动配置（默认关闭）。
 * <p>排序在 starter-mybatis 之前：column 模式的 ColumnTenantStrategy 同时是
 * TenantLineHandler，须先注册使 mybatis 默认实现按 @ConditionalOnMissingBean 让位。
 * 所有跨模块 Bean 经 ObjectProvider 延迟解析（历史经验：禁用 @ConditionalOnBean
 * 跨自动配置直接注入）。
 */
@AutoConfiguration(before = MybatisPlusAutoConfiguration.class)
@ConditionalOnProperty(prefix = "pivotos.tenant", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenantProperties.class)
public class TenantAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TenantAutoConfiguration.class);

    /**
     * 默认租户解析器（LoginUser.tenantId → 请求头），业务侧可注册 Bean 整体替换
     */
    @Bean
    @ConditionalOnMissingBean(TenantResolver.class)
    public TenantResolver tenantResolver(TenantProperties properties) {
        log.info("[PivotOS] 多租户解析器：默认实现（LoginUser.tenantId → {} 请求头）", properties.getHeaderName());
        return new DefaultTenantResolver(properties);
    }

    /**
     * 字段隔离策略：同时注册为增强版 TenantLineHandler，替换 starter-mybatis 默认实现
     */
    @Bean
    @ConditionalOnMissingBean(TenantStrategy.class)
    @ConditionalOnProperty(prefix = "pivotos.tenant", name = "mode", havingValue = "column", matchIfMissing = true)
    public ColumnTenantStrategy columnTenantStrategy(TenantProperties properties,
                                                     ObjectProvider<MybatisProperties> mybatisPropertiesProvider) {
        MybatisProperties mybatisProperties = mybatisPropertiesProvider.getIfAvailable(MybatisProperties::new);
        log.info("[PivotOS] 多租户已启用：mode=column，租户列 {}，忽略表 {} 张（内置 sys_* + 追加）",
                mybatisProperties.getTenantColumn(), properties.effectiveIgnoreTables().size());
        return new ColumnTenantStrategy(properties, mybatisProperties);
    }

    /**
     * Schema 隔离策略：dynamic-datasource 按租户路由
     */
    @Bean
    @ConditionalOnMissingBean(TenantStrategy.class)
    @ConditionalOnProperty(prefix = "pivotos.tenant", name = "mode", havingValue = "schema")
    public SchemaTenantStrategy schemaTenantStrategy(TenantProperties properties) {
        log.info("[PivotOS] 多租户已启用：mode=schema");
        SchemaTenantStrategy strategy = new SchemaTenantStrategy(properties);
        strategy.logEffective();
        return strategy;
    }

    /**
     * 数据源隔离策略：dynamic-datasource 按租户路由
     */
    @Bean
    @ConditionalOnMissingBean(TenantStrategy.class)
    @ConditionalOnProperty(prefix = "pivotos.tenant", name = "mode", havingValue = "datasource")
    public DatasourceTenantStrategy datasourceTenantStrategy(TenantProperties properties) {
        log.info("[PivotOS] 多租户已启用：mode=datasource");
        DatasourceTenantStrategy strategy = new DatasourceTenantStrategy(properties);
        strategy.logEffective();
        return strategy;
    }

    /**
     * 租户上下文绑定过滤器：紧随 auth LoginContextFilter（+20）之后
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(
            TenantProperties properties, TenantResolver tenantResolver, TenantStrategy tenantStrategy) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(
                new TenantContextFilter(properties, tenantResolver, tenantStrategy));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        registration.addUrlPatterns("/*");
        log.info("[PivotOS] 租户上下文过滤器已注册：order={}，忽略接口 {}，strict={}",
                Ordered.HIGHEST_PRECEDENCE + 30, properties.getIgnoreUrls(), properties.isStrict());
        return registration;
    }
}
