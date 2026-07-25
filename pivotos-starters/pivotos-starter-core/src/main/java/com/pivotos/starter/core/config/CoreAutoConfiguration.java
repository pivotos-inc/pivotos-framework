package com.pivotos.starter.core.config;

import com.pivotos.common.api.context.ContextFacade;
import com.pivotos.starter.core.context.ContextExecutor;
import com.pivotos.starter.core.context.ScopedValueContextFacade;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 核心自动配置：ContextFacade 实现 + 上下文感知虚拟线程执行器
 */
@AutoConfiguration
public class CoreAutoConfiguration {

    /**
     * 上下文门面实现（ScopedValue）
     */
    @Bean
    @ConditionalOnMissingBean(ContextFacade.class)
    public ContextFacade contextFacade() {
        return new ScopedValueContextFacade();
    }

    /**
     * 全局异步执行器：虚拟线程 + 上下文透传。
     * 业务异步任务（含 @Async）应使用本 Bean，禁止自建线程池。
     */
    @Bean(name = "contextExecutor", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "contextExecutor")
    public ExecutorService contextExecutor() {
        return ContextExecutor.wrap(Executors.newVirtualThreadPerTaskExecutor());
    }
}
