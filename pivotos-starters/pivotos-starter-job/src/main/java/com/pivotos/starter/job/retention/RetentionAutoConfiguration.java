package com.pivotos.starter.job.retention;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.pivotos.starter.job.config.JobAutoConfiguration;

/**
 * 留存策略公共装配（<strong>无条件</strong>：注册表与配置一律可用）。
 * <p>
 * 刻意不加 {@code @ConditionalOnProperty(pivotos.retention.enabled)}：
 * 一旦加了，关掉总开关时 {@link RetentionJobRegistry} 这个 Bean 就不存在，
 * 各插件 Job 的构造注入会直接失败——「关功能」变成了「起不来」。
 * 开关语义放在执行期（模板返回 skipped 报告），装配期永远可用。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@AutoConfiguration
@AutoConfigureAfter(JobAutoConfiguration.class)
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionAutoConfiguration {

    @Bean
    public RetentionJobRegistry retentionJobRegistry() {
        return new RetentionJobRegistry();
    }
}
