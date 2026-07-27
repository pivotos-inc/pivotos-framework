package com.pivotos.starter.web.config;

import com.pivotos.starter.web.handler.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理自动配置。
 * 注册路径唯一化（S9 决议）：Handler 类上的 @RestControllerAdvice 是 Spring MVC 发现
 * @ExceptionHandler 的功能标记，不可删除；Bean 创建只走本自动配置。
 * @ConditionalOnMissingBean 为防御兜底——若 S8"Starter 严禁组件扫描"决议被破坏，
 * 本 Bean 自动退让，任何路径下恰好一个实例。
 */
@AutoConfiguration
@RestControllerAdvice
public class WebExceptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
