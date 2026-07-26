package com.pivotos.starter.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import com.pivotos.starter.auth.filter.LoginContextFilter;
import com.pivotos.starter.auth.handler.SaTokenExceptionHandler;
import com.pivotos.starter.auth.support.AuthPermissionProvider;
import com.pivotos.starter.auth.support.StpInterfaceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 认证体系自动配置：
 * 登录上下文绑定 / 注解鉴权（SaInterceptor）/ 权限数据桥 / 异常统一。
 */
@AutoConfiguration
public class AuthAutoConfiguration {

    /**
     * LoginContext 绑定过滤器：排在 TraceIdFilter / XssFilter 之后
     */
    @Bean
    public FilterRegistrationBean<LoginContextFilter> loginContextFilterRegistration() {
        FilterRegistrationBean<LoginContextFilter> registration = new FilterRegistrationBean<>(new LoginContextFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * Sa-Token 注解鉴权拦截器：启用 @SaCheckLogin / @SaCheckPermission 等注解。
     * 不做全局路由拦截——登录要求由各端点注解显式声明（公开接口天然放行）。
     */
    @Bean
    public WebMvcConfigurer saTokenWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
            }
        };
    }

    /**
     * 权限数据桥
     */
    @Bean
    public StpInterface stpInterface(AuthPermissionProvider provider) {
        return new StpInterfaceImpl(provider);
    }

    /**
     * 默认权限数据源：空实现（S7 system Plugin 提供真实实现后自动替换）。
     * P0 demo 阶段 @SaCheckPermission 一律拒绝——正好用来验证 403 统一体。
     */
    @Bean
    @ConditionalOnMissingBean(AuthPermissionProvider.class)
    public AuthPermissionProvider emptyAuthPermissionProvider() {
        return new AuthPermissionProvider() {
            @Override
            public List<String> getPermissions(Object loginId, String loginType) {
                return List.of();
            }

            @Override
            public List<String> getRoles(Object loginId, String loginType) {
                return List.of();
            }
        };
    }

    /**
     * Sa-Token 异常统一转换
     */
    @Bean
    public SaTokenExceptionHandler saTokenExceptionHandler() {
        return new SaTokenExceptionHandler();
    }
}
