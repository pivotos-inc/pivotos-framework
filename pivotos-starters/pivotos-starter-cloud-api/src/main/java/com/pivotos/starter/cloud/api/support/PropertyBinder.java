package com.pivotos.starter.cloud.api.support;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.function.Supplier;

/**
 * 在容器刷新早期（BFPP 阶段）读取配置的唯一入口。
 *
 * <p><b>为什么不能用「把 {@code @ConfigurationProperties} Bean 注入 BFPP」</b>：
 * {@code BeanDefinitionRegistryPostProcessor} 在 refresh 早期就要实例化，它的构造参数会被提前解析，
 * 于是那个 properties Bean 先于 {@code ConfigurationPropertiesBindingPostProcessor} 被创建出来 ——
 * 绑定被整体跳过，Bean 里全是默认值，而<b>不报错、不告警</b>。
 * 表象就是「yml 明明配了却不生效」，且只在需要替换 Bean 定义的通道（local / cloud）上出现。
 *
 * <p>用 {@link Binder} 直接绑 Environment，语义与正常绑定一致（同一套 relaxed binding、
 * 同一套嵌套 POJO 规则），但不依赖 BPP 的注册时机。
 */
public final class PropertyBinder {

    private PropertyBinder() {
    }

    /**
     * 按前缀绑定配置；绑定不到（前缀完全不存在）时返回 {@code fallback} 产出的默认值。
     */
    public static <T> T bind(Environment environment, String prefix, Class<T> type, Supplier<T> fallback) {
        if (environment == null) {
            return fallback.get();
        }
        return Binder.get(environment).bind(prefix, Bindable.of(type)).orElseGet(fallback);
    }
}
