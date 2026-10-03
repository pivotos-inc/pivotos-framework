package com.pivotos.starter.cloud.local.rpc;

import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.support.FacadeProxySupport;
import com.pivotos.starter.cloud.api.support.PropertyBinder;
import com.pivotos.starter.cloud.local.config.LocalCloudProperties;
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
 * 把指定 Facade 的本地实现替换成自研 HTTP 代理的注册器。
 *
 * <p>替换动作委托给 {@link FacadeProxySupport#replace}（与 cloud 通道共用同一套语义），
 * 本类只负责「造出 local 通道的代理定义」与「proxied 为空时零行为」。
 *
 * <p>结果：注入 Facade 的地方拿到代理；{@code /__rpc/invoke} 服务端按名取
 * {@code $Local} 调真实实现——调用方代码零改动。
 *
 * <p><b>配置为什么走 {@link PropertyBinder} 而不是注入 properties Bean</b>：本类是 BFPP，
 * 构造参数会在 refresh 早期被解析，把 {@code LocalCloudProperties} / {@code CloudProperties}
 * 在绑定后处理器之前实例化出来 —— 结果是 yml 里配的 {@code proxied}、{@code instances}、
 * {@code context.internal-token} 全部静默失效（不报错、不告警，只给你默认值）。
 * 这是 V3-S2 L4 接熔断时实测抓到的既有缺陷，两个通道同病，一并按同一方式修掉。
 */
public class FacadeRpcRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(FacadeRpcRegistrar.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        LocalCloudProperties localProps = localProperties();
        Set<String> proxied = localProps.proxiedInterfaces();
        if (proxied.isEmpty()) {
            log.info("[CLOUD][local] 未配置 proxied，Facade 调用保持进程内（零行为）");
            return;
        }
        CloudProperties cloudProps = cloudProperties();
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        for (String ifaceName : proxied) {
            try {
                Class<?> iface = ClassUtils.forName(ifaceName, classLoader);
                boolean replaced = FacadeProxySupport.replace(registry, classLoader, iface,
                    target -> proxyDefinition(target, localProps, cloudProps));
                if (replaced) {
                    log.info("[CLOUD][local] {} 已切换为自研 HTTP 远程代理（本地实现保留为 <beanName>{}）",
                        iface.getSimpleName(), FacadeProxySupport.LOCAL_SUFFIX);
                } else {
                    log.warn("[CLOUD][local] 未找到 {} 的本地实现 Bean，保持进程内调用", ifaceName);
                }
            } catch (Exception e) {
                // 单个接口替换失败不该拖垮整个启动：降级为进程内调用
                log.warn("[CLOUD][local] Facade 远程替换失败，保持进程内调用：{}", ifaceName, e);
            }
        }
    }

    private RootBeanDefinition proxyDefinition(Class<?> iface,
                                               LocalCloudProperties localProps,
                                               CloudProperties cloudProps) {
        RootBeanDefinition definition = new RootBeanDefinition(FacadeRpcProxyFactoryBean.class);
        definition.getConstructorArgumentValues().addGenericArgumentValue(iface);
        definition.getConstructorArgumentValues().addGenericArgumentValue(localProps);
        definition.getConstructorArgumentValues().addGenericArgumentValue(cloudProps);
        return definition;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 无需处理：替换在 registry 阶段完成
    }

    /** 供日志与测试核对：当前配置的代理接口集合 */
    public Set<String> proxiedInterfaces() {
        return localProperties().proxiedInterfaces();
    }

    private LocalCloudProperties localProperties() {
        return PropertyBinder.bind(environment, "pivotos.cloud.local", LocalCloudProperties.class,
            LocalCloudProperties::new);
    }

    private CloudProperties cloudProperties() {
        return PropertyBinder.bind(environment, "pivotos.cloud", CloudProperties.class, CloudProperties::new);
    }
}
