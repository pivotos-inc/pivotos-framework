package com.pivotos.ai.config;

import com.pivotos.ai.filter.McpEndpointAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * AI 插件 Web 安全装配（S98 A2）：MCP 端点登录防护。
 *
 * <p>过滤器排序紧跟 LoginContextFilter（HIGHEST_PRECEDENCE+20）之后，
 * 确保判定时 LoginContext 已绑定。
 */
@Configuration(proxyBeanMethods = false)
public class AiWebSecurityConfiguration {

    @Bean
    public FilterRegistrationBean<McpEndpointAuthFilter> mcpEndpointAuthFilterRegistration() {
        FilterRegistrationBean<McpEndpointAuthFilter> registration =
                new FilterRegistrationBean<>(new McpEndpointAuthFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 25);
        registration.addUrlPatterns("/sse", "/mcp/message");
        return registration;
    }
}
