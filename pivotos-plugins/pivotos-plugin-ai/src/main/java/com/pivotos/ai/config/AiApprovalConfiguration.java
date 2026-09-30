package com.pivotos.ai.config;

import com.pivotos.ai.orchestrator.ApprovalAdviceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * A4E 审批建议增强配置入口（S117）：注册受控自动预审开关。
 *
 * <p>单独一个配置类而不是并入 {@code AiOrchestratorConfiguration}：
 * 审批建议与工具编排是两个能力面，配置类按能力分置，
 * 避免「改编排配置」时误动审批建议的开关绑定（绑定失效是静默的）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalAdviceProperties.class)
public class AiApprovalConfiguration {
}
