package com.pivotos.starter.cloud.api.config.condition;

import com.pivotos.starter.cloud.api.CloudProvider;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 按 {@code pivotos.cloud.provider} 装配：只有配置指向本通道时才生效。
 *
 * <p>为什么不用 {@code @ConditionalOnProperty}：
 * 通道判定要同时看「provider 是否匹配」与「总开关 enabled」，且 provider 需要宽松解析
 * （未知值回落 local），写成一个 Condition 才能三通道共用同一套语义。
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(CloudProviderCondition.class)
public @interface ConditionalOnCloudProvider {

    /**
     * 期望生效的通道（可多值：命中其一即装配）。
     *
     * <p>为什么支持多值：能力并非「一个通道一份」——例如出站身份传播拦截器对
     * {@code cloud} 与 {@code alibaba} 都要生效（两者都用 Feign 出站），
     * 若只能写单值，就只能在 alibaba 形态下复制一份装配，或被迫把整个 SC 自动配置
     * 放宽到 alibaba（会连带装配出与 Nacos 通道重复的实例提供者 Bean）。
     */
    CloudProvider[] value();
}
