package com.pivotos.starter.web.config;

import com.pivotos.starter.web.checker.RepeatSubmitChecker;
import com.pivotos.starter.web.interceptor.RepeatSubmitInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 幂等自动配置：仅当容器存在 RepeatSubmitChecker 实现
 * （由 pivotos-starter-redis 注册）时才装配拦截器——增删依赖 0 改动。
 */
@AutoConfiguration
public class RepeatSubmitAutoConfiguration {

    @Bean
    @ConditionalOnBean(RepeatSubmitChecker.class)
    public WebMvcConfigurer repeatSubmitWebMvcConfigurer(RepeatSubmitChecker checker) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RepeatSubmitInterceptor(checker)).addPathPatterns("/**");
            }
        };
    }
}
