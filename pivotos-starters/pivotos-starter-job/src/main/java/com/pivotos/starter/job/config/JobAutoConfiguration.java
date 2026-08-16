package com.pivotos.starter.job.config;

import com.pivotos.starter.job.client.XxlJobAdminClient;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * XXL-Job 自动配置
 * <p>
 * 条件: classpath 存在 XxlJobSpringExecutor + pivotos.job.admin-addresses 已配置
 */
@AutoConfiguration
@ConditionalOnClass(XxlJobSpringExecutor.class)
@ConditionalOnProperty(prefix = "pivotos.job", name = "admin-addresses")
@EnableConfigurationProperties(JobProperties.class)
public class JobAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JobAutoConfiguration.class);

    @Bean
    public XxlJobSpringExecutor xxlJobSpringExecutor(JobProperties properties,
                                                     ObjectProvider<XxlJobSpringExecutorCustomizer> customizers) {
        log.info("[PivotOS] XXL-Job 调度装配：adminAddresses={}, appName={}, port={}",
                properties.getAdminAddresses(), properties.getAppName(), properties.getPort());

        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(properties.getAdminAddresses());
        executor.setAppname(properties.getAppName());
        executor.setPort(properties.getPort());

        if (properties.getAccessToken() != null && !properties.getAccessToken().isBlank()) {
            executor.setAccessToken(properties.getAccessToken());
        }
        if (properties.getAddress() != null && !properties.getAddress().isBlank()) {
            executor.setAddress(properties.getAddress());
        }
        if (properties.getIp() != null && !properties.getIp().isBlank()) {
            executor.setIp(properties.getIp());
        }
        executor.setLogPath(properties.getLogPath());
        executor.setLogRetentionDays(properties.getLogRetentionDays());

        // 允许业务侧自定义注册后回调
        customizers.forEach(c -> c.customize(executor));

        return executor;
    }

    /**
     * XXL-Job executor 自定义扩展点
     */
    @FunctionalInterface
    public interface XxlJobSpringExecutorCustomizer {
        void customize(XxlJobSpringExecutor executor);
    }

    /**
     * XXL-Job admin Open API 客户端（仅执行器装配时生效）
     */
    @Bean
    public XxlJobAdminClient xxlJobAdminClient(JobProperties properties) {
        return new XxlJobAdminClient(properties);
    }

    /**
     * 日志保留天数也单独暴露成一个 bean，方便在 handler 中注入判断
     */
    @Bean
    public int jobLogRetentionDays(JobProperties properties) {
        return properties.getLogRetentionDays();
    }
}
