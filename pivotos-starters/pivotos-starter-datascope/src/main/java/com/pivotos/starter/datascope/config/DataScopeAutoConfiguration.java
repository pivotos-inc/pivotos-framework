package com.pivotos.starter.datascope.config;

import com.pivotos.starter.datascope.interceptor.DataPermissionInterceptor;
import com.pivotos.starter.datascope.properties.DataScopeProperties;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 数据权限自动配置。
 * <p>
 * 将 {@link DataPermissionInterceptor}（MyBatis Plugin）注册到所有 SqlSessionFactory。
 * 业务侧数据权限解析（用户角色 → DataScopeInfo）由 plugin-system 中的 {@code DataScopeHelper} 完成，
 * ScopedValue 绑定由 plugin-system 中的 {@code DataScopeBindingFilter} 完成。
 * <p>
 * 装配验证：增删 starter-datascope 依赖后，0 代码改动即可启用/禁用数据权限功能。
 * <p>
 * 与 TenantLineInnerInterceptor 共存：tenant 列过滤（executor.query 阶段）先于
 * 数据权限行过滤（statementHandler.prepare 阶段），两者互不冲突。
 *
 * @author PivotOS Team
 */
@AutoConfiguration
@ConditionalOnClass({SqlSessionFactory.class})
@ConditionalOnProperty(prefix = "pivotos.datascope", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DataScopeProperties.class)
public class DataScopeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DataScopeAutoConfiguration.class);

    @Bean
    public DataPermissionInterceptor dataPermissionInterceptor() {
        log.info("[PivotOS] 数据权限 MyBatis 插件已创建");
        return new DataPermissionInterceptor();
    }

    /**
     * 将 DataPermissionInterceptor 注册为 MyBatis Plugin 到每个 SqlSessionFactory。
     * <p>
     * 注册时机：SqlSessionFactory Bean 初始化完成后，通过 Configuration.addInterceptor()
     * 注入 MyBatis 原生拦截器链。MyBatis-Plus 的 MybatisPlusInterceptor 也走同一条链，
     * 执行顺序：MybatisPlusInterceptor（含 TenantLine）→ DataPermissionInterceptor。
     */
    @Bean
    public static BeanPostProcessor dataScopePluginRegistrar(DataPermissionInterceptor dataPermissionInterceptor) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SqlSessionFactory sqlSessionFactory) {
                    sqlSessionFactory.getConfiguration().addInterceptor(dataPermissionInterceptor);
                    log.info("[PivotOS] 数据权限插件已注册到 SqlSessionFactory [{}]", beanName);
                }
                return bean;
            }
        };
    }
}
