package com.pivotos.starter.job.config;

import com.pivotos.starter.job.api.JobHandlerRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 定时任务支撑装配（无条件）：手动触发注册表。
 * <p>
 * 与 JobAutoConfiguration（XXL-Job 执行器，需配置 admin-addresses）解耦，
 * 保证无调度中心环境下注册表仍可用。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@AutoConfiguration
public class JobSupportAutoConfiguration {

    @Bean
    public JobHandlerRegistry jobHandlerRegistry() {
        return new JobHandlerRegistry();
    }
}
