package com.pivotos.starter.metrics.config;

import com.pivotos.starter.metrics.support.BusinessMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 指标总开关关闭时的兜底装配（V3.1-F · P1-B8-1）。
 *
 * <p>{@code pivotos.metrics.enabled=false} 时，本类仍然提供一个 {@link BusinessMetrics}
 * ——但是 registry == null 的 no-op 实例。目的：业务代码（检索模板 / AI 计量装饰器）
 * 无条件注入 {@code BusinessMetrics} 即可，不会因为「关了一个可观测开关」而启动失败。
 * 关开关的正确后果是「不出指标」，不是「起不来」。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · P1-B8-1）
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "pivotos.metrics", name = "enabled", havingValue = "false")
public class MetricsNoOpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BusinessMetrics businessMetrics() {
        return new BusinessMetrics(null);
    }
}
