package com.pivotos.starter.cloud.sc.feign;

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
 */
public class FeignFacadeFactoryBean implements FactoryBean<Object>, ApplicationContextAware {

    private final Class<?> iface;
    private final ScCloudProperties properties;

    private ApplicationContext applicationContext;
    private volatile Object client;

    public FeignFacadeFactoryBean(Class<?> iface, ScCloudProperties properties) {
        this.iface = iface;
        this.properties = properties;
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
                    client = feignBuilder.build();
                }
            }
        }
        return client;
    }

    @Override
    public Class<?> getObjectType() {
        return iface;
    }
}
