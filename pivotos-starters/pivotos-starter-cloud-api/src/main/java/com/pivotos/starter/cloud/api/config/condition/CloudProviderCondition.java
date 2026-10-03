package com.pivotos.starter.cloud.api.config.condition;

import com.pivotos.starter.cloud.api.CloudProvider;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * {@link ConditionalOnCloudProvider} 的判定实现。
 *
 * <p>两个条件同时成立才装配：
 * <ol>
 *   <li>{@code pivotos.cloud.enabled} 不为 false（缺省 true）；</li>
 *   <li>{@code pivotos.cloud.provider} 宽松解析后 == 注解声明的通道。</li>
 * </ol>
 *
 * <p>provider 未配 / 配错都按 local 解析（{@link CloudProvider#of} 的兜底），
 * 与启动日志里打印的「实际生效通道」保持同一口径，不会出现「配置看到 A、实际装配 B」。
 */
public class CloudProviderCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnCloudProvider.class.getName());
        CloudProvider expected = attrs == null || attrs.get("value") == null
            ? CloudProvider.LOCAL
            : (CloudProvider) attrs.get("value");
        String enabled = context.getEnvironment().getProperty("pivotos.cloud.enabled", "true");
        if ("false".equalsIgnoreCase(enabled)) {
            return false;
        }
        String raw = context.getEnvironment().getProperty("pivotos.cloud.provider");
        return CloudProvider.of(raw) == expected;
    }
}
