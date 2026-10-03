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

    /** 期望生效的通道 */
    CloudProvider value();
}
