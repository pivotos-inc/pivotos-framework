package com.pivotos.ai.config;

import com.pivotos.ai.orchestrator.OrchestratorProperties;
import com.pivotos.ai.orchestrator.ToolPlanValidator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 工具多步编排装配（A5-1 / S116）。
 *
 * <p>沿用 ai-coding 的 {@code LocateConfig} 口径：由插件侧 {@code @Configuration} 显式注册
 * {@code @ConfigurationProperties}，避免散落在 starter 里。
 *
 * <p>执行器 / 草案服务 / LLM 客户端均为 {@code @Component}，构造注入即可生效；
 * 本类只负责装配与配置强绑定的 {@link ToolPlanValidator}（步骤上限取自配置）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrchestratorProperties.class)
@RequiredArgsConstructor
public class AiOrchestratorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestratorConfiguration.class);

    @Bean
    public ToolPlanValidator toolPlanValidator(OrchestratorProperties properties) {
        log.info("[PivotOS] AI 编排装配：enabled={} maxSteps={} excludedTools={}",
                properties.isEnabled(), properties.getMaxSteps(), properties.getExcludedTools());
        return new ToolPlanValidator(properties);
    }
}
