package com.pivotos.starter.cloud.api.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Facade 远程替换的公共工具测试。
 *
 * <p>核心断言是那两个踩过坑的细节：
 * ① 本地实现必须以 {@code $Local} 保留；② 该别名必须关闭 autowire 候选，
 * 否则 by-type 注入点会同时看到两个候选 → NoUniqueBeanDefinitionException（S133 踩坑 K3）。
 */
class FacadeProxySupportTest {

    public interface SampleFacade {

        String ping();
    }

    public static class SampleLocalFacade implements SampleFacade {

        @Override
        public String ping() {
            return "pong";
        }
    }

    @Test
    void replaces_original_bean_and_keeps_local_alias() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBeanDefinition("sampleFacade", new RootBeanDefinition(SampleLocalFacade.class));

        boolean replaced = FacadeProxySupport.replace(context, getClass().getClassLoader(), SampleFacade.class,
            iface -> new RootBeanDefinition(SampleLocalFacade.class));

        assertThat(replaced).isTrue();
        assertThat(context.getBeanDefinitionNames())
            .as("原 beanName 仍在（注入点不受影响），本地实现以 $Local 保留")
            .contains("sampleFacade", "sampleFacade" + FacadeProxySupport.LOCAL_SUFFIX);
        assertThat(context.getBeanDefinition("sampleFacade" + FacadeProxySupport.LOCAL_SUFFIX).isAutowireCandidate())
            .as("$Local 必须关闭 autowire 候选，否则注入点会出现两个候选")
            .isFalse();
    }

    @Test
    void returns_false_when_no_local_implementation() {
        GenericApplicationContext context = new GenericApplicationContext();
        boolean replaced = FacadeProxySupport.replace(context, getClass().getClassLoader(), SampleFacade.class,
            iface -> new RootBeanDefinition(SampleLocalFacade.class));
        assertThat(replaced).as("没有本地实现时必须保持进程内调用，不报错").isFalse();
    }
}
