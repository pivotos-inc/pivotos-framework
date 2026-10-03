package com.pivotos.starter.cloud.sc.feign;

import com.pivotos.starter.cloud.api.support.FacadeProxySupport;
import com.pivotos.starter.cloud.sc.config.ScCloudProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;

import java.util.Set;

/**
 * Spring Cloud 通道的 Facade 替换注册器：把本地实现换成 Feign 客户端。
 *
 * <p>与 local 通道共用 {@link FacadeProxySupport#replace} 的替换语义；
 * 差别只在「代理定义的产出方式」——这里是 {@link FeignFacadeFactoryBean}。
 */
public class FeignFacadeRegistrar implements BeanDefinitionRegistryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(FeignFacadeRegistrar.class);

    private final ScCloudProperties properties;

    public FeignFacadeRegistrar(ScCloudProperties properties) {
        this.properties = properties;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Set<String> proxied = properties.proxiedInterfaces();
        if (proxied.isEmpty()) {
            log.info("[CLOUD][cloud] 未配置 proxied，Facade 调用保持进程内（零行为）");
            return;
        }
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        for (String ifaceName : proxied) {
            try {
                Class<?> iface = ClassUtils.forName(ifaceName, classLoader);
                boolean replaced = FacadeProxySupport.replace(registry, classLoader, iface,
                    target -> proxyDefinition(target));
                if (replaced) {
                    log.info("[CLOUD][cloud] {} 已切换为 Feign 客户端（serviceId={}，url={}）",
                        iface.getSimpleName(), properties.getServiceId(),
                        properties.getUrl() == null || properties.getUrl().isBlank() ? "(走负载均衡)" : properties.getUrl());
                } else {
                    log.warn("[CLOUD][cloud] 未找到 {} 的本地实现 Bean，保持进程内调用", ifaceName);
                }
            } catch (Exception e) {
                log.warn("[CLOUD][cloud] Facade 远程替换失败，保持进程内调用：{}", ifaceName, e);
            }
        }
    }

    private RootBeanDefinition proxyDefinition(Class<?> iface) {
        RootBeanDefinition definition = new RootBeanDefinition(FeignFacadeFactoryBean.class);
        definition.getConstructorArgumentValues().addGenericArgumentValue(iface);
        definition.getConstructorArgumentValues().addGenericArgumentValue(properties);
        return definition;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 无需处理
    }

    /** 供日志与测试核对 */
    public Set<String> proxiedInterfaces() {
        return properties.proxiedInterfaces();
    }
}
