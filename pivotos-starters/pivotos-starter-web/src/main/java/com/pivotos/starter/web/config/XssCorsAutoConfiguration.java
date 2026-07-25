package com.pivotos.starter.web.config;

import com.pivotos.starter.web.cors.CorsProperties;
import com.pivotos.starter.web.xss.XssFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * XSS 过滤 + CORS 自动配置
 */
@AutoConfiguration
@EnableConfigurationProperties(CorsProperties.class)
public class XssCorsAutoConfiguration {

    /**
     * XSS 过滤器，优先级仅次于 TraceIdFilter
     */
    @Bean
    public FilterRegistrationBean<XssFilter> xssFilterRegistration() {
        FilterRegistrationBean<XssFilter> registration = new FilterRegistrationBean<>(new XssFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * CORS 全局映射
     */
    @Bean
    public WebMvcConfigurer corsWebMvcConfigurer(CorsProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns(properties.getAllowedOriginPatterns().toArray(new String[0]))
                        .allowedMethods(properties.getAllowedMethods().toArray(new String[0]))
                        .allowedHeaders("*")
                        .allowCredentials(true)
                        .maxAge(properties.getMaxAge());
            }
        };
    }
}
