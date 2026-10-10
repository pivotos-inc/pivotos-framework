package com.pivotos.starter.cloud.api.support;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Facade 远程替换的公共工具：local / cloud 两个通道共用同一套替换语义。
 *
 * <p>抽到这里的原因很直接：替换逻辑若各写一份，「$Local 必须 setAutowireCandidate(false)」
 * 这类踩过坑的细节（S133 踩坑 K3：漏了它就直接 NoUniqueBeanDefinitionException）
 * 必然在其中一份里漂移，而漂移的形态是「启动期偶发失败」，很难查。
 */
public final class FacadeProxySupport {

    /** 本地实现别名后缀：代理替换后，真实实现以 {@code <beanName>$Local} 保留 */
    public static final String LOCAL_SUFFIX = "$Local";

    private FacadeProxySupport() {
    }

    /**
     * 找到某 Facade 接口的本地实现 Bean 名；找不到返回 null。
     * 跳过 {@code $Local} 结尾的别名、无 beanClassName 的定义（工厂方法产出）。
     */
    public static String findLocalBeanName(BeanDefinitionRegistry registry, ClassLoader classLoader, Class<?> iface) {
        for (String name : registry.getBeanDefinitionNames()) {
            if (name.endsWith(LOCAL_SUFFIX)) {
                continue;
            }
            BeanDefinition definition = registry.getBeanDefinition(name);
            String beanClassName = definition.getBeanClassName();
            if (beanClassName == null || beanClassName.equals(iface.getName())) {
                continue;
            }
            try {
                Class<?> type = ClassUtils.forName(beanClassName, classLoader);
                if (iface.isAssignableFrom(type)) {
                    return name;
                }
            } catch (Exception ignored) {
                // 类加载失败（optional 依赖缺席等）：跳过该定义
            }
        }
        return null;
    }

    /**
     * 把本地实现换成远程代理：
     * <ol>
     *   <li>复制原定义，以 {@code <beanName>$Local} 注册，并关闭 autowire 候选——
     *       否则 by-type 注入点同时看到两个候选，直接 NoUniqueBeanDefinitionException；</li>
     *   <li>原 beanName 换成 {@code proxyDefinitionFactory} 产出的定义。</li>
     * </ol>
     *
     * @return 是否完成替换（false = 没找到本地实现，保持进程内调用）
     */
    public static boolean replace(BeanDefinitionRegistry registry,
                                  ClassLoader classLoader,
                                  Class<?> iface,
                                  Function<Class<?>, RootBeanDefinition> proxyDefinitionFactory) {
        return replaceAndReturnLocalBeanName(registry, classLoader, iface,
            (proxyIface, localBeanName) -> proxyDefinitionFactory.apply(proxyIface)) != null;
    }

    /**
     * 同 {@link #replace}，但额外返回被保留下来的本地实现 Bean 名，并把它交给代理定义工厂。
     *
     * <p>为什么需要它：熔断降级口径 {@code fallback-mode=local} 要在熔断打开时回落到进程内实现，
     * 而那个实现此刻只剩一个 {@code <beanName>$Local} 别名、且已关闭 autowire 候选 ——
     * 只有拿到确切 Bean 名才取得到（按类型取会被「非候选」挡掉）。
     *
     * @param proxyDefinitionFactory 入参为 {@code (接口, 本地实现Bean名)}，产出代理定义
     * @return 本地实现 Bean 名（{@code $Local} 后缀）；{@code null} 表示没找到本地实现、未做替换
     */
    public static String replaceAndReturnLocalBeanName(BeanDefinitionRegistry registry,
                                                       ClassLoader classLoader,
                                                       Class<?> iface,
                                                       BiFunction<Class<?>, String, RootBeanDefinition> proxyDefinitionFactory) {
        String localBeanName = findLocalBeanName(registry, classLoader, iface);
        if (localBeanName == null) {
            return null;
        }
        // cloneBeanDefinition() 保真复制（构造参数/属性值/作用域一并带走）。
        // 不能用 new RootBeanDefinition(BeanDefinition)：Spring 7 起该构造器非 public。
        AbstractBeanDefinition localDefinition = cloneOf(registry.getBeanDefinition(localBeanName));
        localDefinition.setAutowireCandidate(false);
        registry.registerBeanDefinition(localBeanName + LOCAL_SUFFIX, localDefinition);

        String retainedName = localBeanName + LOCAL_SUFFIX;
        registry.removeBeanDefinition(localBeanName);
        registry.registerBeanDefinition(localBeanName, proxyDefinitionFactory.apply(iface, retainedName));
        return retainedName;
    }

    /** 复制 Bean 定义；非 AbstractBeanDefinition 时退化为「只带类名」的等价定义 */
    private static AbstractBeanDefinition cloneOf(BeanDefinition original) {
        if (original instanceof AbstractBeanDefinition abstractDefinition) {
            return abstractDefinition.cloneBeanDefinition();
        }
        return new RootBeanDefinition(original.getBeanClassName());
    }
}
