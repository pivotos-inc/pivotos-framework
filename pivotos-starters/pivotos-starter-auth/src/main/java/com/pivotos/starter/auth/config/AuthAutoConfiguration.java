package com.pivotos.starter.auth.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import com.pivotos.starter.auth.filter.AnonymousAccessGuardFilter;
import com.pivotos.starter.auth.filter.LoginContextFilter;
import com.pivotos.starter.auth.handler.SaTokenExceptionHandler;
import com.pivotos.starter.auth.support.AuthPermissionProvider;
import com.pivotos.starter.auth.support.StpInterfaceImpl;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 认证体系自动配置：
 * 登录上下文绑定 / 注解鉴权（SaInterceptor）/ 权限数据桥 / 异常统一。
 */
@AutoConfiguration
@EnableConfigurationProperties(AuthGuardProperties.class)
@RestControllerAdvice
public class AuthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthAutoConfiguration.class);

    /**
     * Sa-Token 持久化到 Redis：sa-token-redisson 只提供实现类、不带自动装配，
     * 不显式注册则回落内存实现（重启后所有登录态丢失，S13 验收实测踩坑）。
     * 用 ObjectProvider 延迟解析规避 @ConditionalOnBean 的装配顺序敏感
     * （Redisson 4.x 由 RedissonAutoConfigurationV4 注册客户端，顺序不可控）；
     * 无 RedissonClient（未装配 redis starter）时显式回落默认内存实现。
     */
    @Bean
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnMissingBean(SaTokenDao.class)
    public SaTokenDao saTokenDao(ObjectProvider<RedissonClient> redissonClientProvider) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            log.warn("[PivotOS] 未发现 RedissonClient，Sa-Token 使用内存持久化（重启后登录态丢失）");
            return new SaTokenDaoDefaultImpl();
        }
        log.info("[PivotOS] Sa-Token 持久化：Redis（SaTokenDaoForRedisson）");
        return new SaTokenDaoForRedisson(redissonClient);
    }

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
     * 匿名访问闸门（v3.1.0 生产化 · A4）：路由层兜底，治「第三方 jar 自带端点零鉴权」。
     *
     * <p>只守配置里显式登记的前缀（默认 {@code /warm-flow/**}），不动 PivotOS 自有端点——
     * 自有端点的鉴权仍是「注解 + 业务代码取 LoginContext」的既有口径，两种机制各管一段，
     * 避免一刀切把登录/探活/回调这些本来就该匿名的端点误伤。
     *
     * <p>order 必须<b>大于</b> {@code LoginContextFilter}（{@code HIGHEST_PRECEDENCE + 20}）：
     * 登录判定读的是 LoginContext，过滤器链里它必须先跑。
     * 取 +200 也是为了排在旧设计器守卫（+100）之后，不抢它对 {@code /warm-flow-ui} 的 410 语义。
     */
    @Bean
    @ConditionalOnProperty(prefix = "pivotos.auth.guard", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<AnonymousAccessGuardFilter> anonymousAccessGuardFilter(
            AuthGuardProperties guardProperties) {
        AnonymousAccessGuardFilter filter = new AnonymousAccessGuardFilter(
                guardProperties.getPaths(), guardProperties.getExcludePaths(), guardProperties.getMessage());
        FilterRegistrationBean<AnonymousAccessGuardFilter> registration =
                new FilterRegistrationBean<>(filter);
        // 挂在 /* 上、由过滤器内部用 Ant 语法判定：Servlet 的 urlPatterns 只认 /warm-flow/*，
        // 不认 **（写成 ** 会被当字面量，守卫静默失效），且例外前缀也无法表达。
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 200);
        registration.setName("anonymousAccessGuardFilter");
        return registration;
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
     * Sa-Token 异常统一转换（注册路径唯一化，同 WebExceptionAutoConfiguration 注释）
     */
    @Bean
    @ConditionalOnMissingBean(SaTokenExceptionHandler.class)
    public SaTokenExceptionHandler saTokenExceptionHandler() {
        return new SaTokenExceptionHandler();
    }
}
