package com.pivotos.starter.cloud.sc.feign;

import com.pivotos.starter.cloud.api.support.FacadeProxySupport;
import com.pivotos.starter.cloud.api.support.PropertyBinder;
import com.pivotos.starter.cloud.sc.config.ScCloudProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.util.Set;

/**
 * Spring Cloud 通道的 Facade 替换注册器：把本地实现换成 Feign 客户端。
 *
 * <p>与 local 通道共用 {@link FacadeProxySupport#replace} 的替换语义；
 * 差别只在「代理定义的产出方式」——这里是 {@link FeignFacadeFactoryBean}。
 *
 * <p><b>配置为什么走 {@link PropertyBinder} 而不是注入 properties Bean</b>：
 * 本类是 BFPP，构造参数会被提前实例化，注入 {@code ScCloudProperties} 会让它
 * 逃过绑定后处理器（拿到全默认值，且不报错）——配置写了却不生效。详见 {@link PropertyBinder}。
 */
public class FeignFacadeRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(FeignFacadeRegistrar.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        ScCloudProperties properties = PropertyBinder.bind(environment, "pivotos.cloud.sc",
            ScCloudProperties.class, ScCloudProperties::new);
        Set<String> proxied = properties.proxiedInterfaces();
        if (proxied.isEmpty()) {
            log.info("[CLOUD][cloud] 未配置 proxied，Facade 调用保持进程内（零行为）");
            return;
        }
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        for (String ifaceName : proxied) {
            try {
                Class<?> iface = ClassUtils.forName(ifaceName, classLoader);
                String localBeanName = FacadeProxySupport.replaceAndReturnLocalBeanName(registry, classLoader, iface,
                    (target, retainedLocalBeanName) -> proxyDefinition(target, retainedLocalBeanName, properties));
                if (localBeanName != null) {
                    log.info("[CLOUD][cloud] {} 已切换为 Feign 客户端（serviceId={}，url={}，熔断={}）",
                        iface.getSimpleName(), properties.getServiceId(),
                        properties.getUrl() == null || properties.getUrl().isBlank() ? "(走负载均衡)" : properties.getUrl(),
                        properties.getCircuitBreaker() != null && properties.getCircuitBreaker().isEnabled()
                            ? "已启用" : "未启用");
                } else {
                    log.warn("[CLOUD][cloud] 未找到 {} 的本地实现 Bean，保持进程内调用", ifaceName);
                }
            } catch (Exception e) {
                log.warn("[CLOUD][cloud] Facade 远程替换失败，保持进程内调用：{}", ifaceName, e);
            }
        }
    }

    private RootBeanDefinition proxyDefinition(Class<?> iface, String localBeanName, ScCloudProperties properties) {
        RootBeanDefinition definition = new RootBeanDefinition(FeignFacadeFactoryBean.class);
        definition.getConstructorArgumentValues().addGenericArgumentValue(iface);
        definition.getConstructorArgumentValues().addGenericArgumentValue(properties);
        definition.getConstructorArgumentValues().addGenericArgumentValue(localBeanName);
        return definition;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 无需处理
    }

    /** 供日志与测试核对 */
    public Set<String> proxiedInterfaces() {
        return PropertyBinder.bind(environment, "pivotos.cloud.sc", ScCloudProperties.class,
            ScCloudProperties::new).proxiedInterfaces();
    }
}
