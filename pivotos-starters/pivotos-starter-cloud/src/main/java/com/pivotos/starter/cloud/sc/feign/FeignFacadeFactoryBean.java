package com.pivotos.starter.cloud.sc.feign;

import com.pivotos.starter.cloud.sc.circuitbreaker.CircuitBreakerFacadeProxy;
import com.pivotos.starter.cloud.sc.circuitbreaker.CloudCircuitBreakerSupport;
import com.pivotos.starter.cloud.sc.config.ScCloudProperties;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.StringUtils;

/**
 * 把 Facade 接口包装成 Feign 客户端的 FactoryBean。
 *
 * <p>为什么不用 {@code @FeignClient} 注解：那会把 Spring Cloud 注解打进 {@code -api} 契约模块，
 * 契约层被迫依赖 spring-cloud-openfeign —— 与「契约零中间件依赖」冲突（local 通道就没法用了）。
 * 这里改用 {@link FeignClientBuilder} 在容器里<b>运行时</b>构建，契约接口保持干净。
 *
 * <p>{@code url} 为空时按 {@code serviceId} 走 LoadBalancer（需要注册中心）；
 * 配了 {@code url} 则直连，无注册中心也能跑通（本机验证就走这条）。
 *
 * <p>熔断包装：仅当 {@code pivotos.cloud.sc.circuit-breaker.enabled=true} 且容器里有
 * {@link CloudCircuitBreakerSupport} 时才在 Feign 客户端外再套一层代理；
 * 否则返回 Feign 客户端本体 —— 这是「默认不劣化」的可断言形态（测试里用 {@code assertSame} 钉死）。
 * 支撑 Bean 走 {@code getBeanProvider().getIfAvailable()} 延迟取：本 FactoryBean 的定义由
 * BFPP 阶段注册，那时容器还没刷新完，不能直接注入。
 */
public class FeignFacadeFactoryBean implements FactoryBean<Object>, ApplicationContextAware {

    private final Class<?> iface;
    private final ScCloudProperties properties;
    private final String localBeanName;

    private ApplicationContext applicationContext;
    private volatile Object client;

    public FeignFacadeFactoryBean(Class<?> iface, ScCloudProperties properties, String localBeanName) {
        this.iface = iface;
        this.properties = properties;
        this.localBeanName = localBeanName;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object getObject() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    FeignClientBuilder builder = new FeignClientBuilder(applicationContext);
                    FeignClientBuilder.Builder feignBuilder = builder.forType(iface, properties.getServiceId());
                    if (StringUtils.hasText(properties.getUrl())) {
                        feignBuilder.url(properties.getUrl().trim());
                    }
                    Object feignClient = feignBuilder.build();
                    client = withCircuitBreaker(feignClient);
                }
            }
        }
        return client;
    }

    private Object withCircuitBreaker(Object feignClient) {
        if (applicationContext == null || properties.getCircuitBreaker() == null
            || !properties.getCircuitBreaker().isEnabled()) {
            return feignClient;
        }
        CloudCircuitBreakerSupport support =
            applicationContext.getBeanProvider(CloudCircuitBreakerSupport.class).getIfAvailable();
        if (support == null) {
            return feignClient;
        }
        return CircuitBreakerFacadeProxy.wrap(iface, feignClient, support,
            () -> localBeanName == null ? null : applicationContext.getBean(localBeanName));
    }

    @Override
    public Class<?> getObjectType() {
        return iface;
    }
}
