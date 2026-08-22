package com.pivotos.ai.config;

import com.pivotos.ai.service.AiToolGuardService;
import com.pivotos.ai.service.AiToolInvokeRecorder;
import com.pivotos.ai.tool.GuardedToolCallbackProvider;
import com.pivotos.ai.tool.WorkflowQueryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 工具回调装配（S97 A2 前置验证，S98 权限体系建实）。
 *
 * <p>MethodToolCallbackProvider 扫描工具对象的 @Tool 方法生成 ToolCallback，
 * 再经 {@link GuardedToolCallbackProvider} 逐个包裹「注册检查 → 角色白名单 →
 * 二次确认预检 → 全量审计」守卫，一份产物两条消费链路：
 * <ul>
 *   <li>MCP Server 自动配置（spring-ai-starter-mcp-server-webmvc）拾取容器内
 *       全部 ToolCallbackProvider，经 SSE 端点暴露 tools/list、tools/call；</li>
 *   <li>AiChatServiceImpl 同步对话链路注入本 Bean 挂载 toolCallbacks，
 *       模型可发起 function calling。</li>
 * </ul>
 *
 * <p>本配置保持「单点汇聚全部工具」的唯一入口语义；新增工具类只需在
 * toolObjects(...) 追加对象，守卫与审计自动生效。
 */
@Configuration(proxyBeanMethods = false)
public class AiToolCallbackConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiToolCallbackConfiguration.class);

    @Bean
    public ToolCallbackProvider pivotToolCallbackProvider(WorkflowQueryTools workflowQueryTools,
                                                          AiToolGuardService guardService,
                                                          AiToolInvokeRecorder invokeRecorder) {
        log.info("[PivotOS] AI 工具回调装配：WorkflowQueryTools（守卫式：白名单 + 二次确认 + 审计）");
        ToolCallbackProvider raw = MethodToolCallbackProvider.builder()
                .toolObjects(workflowQueryTools)
                .build();
        return new GuardedToolCallbackProvider(raw, guardService, invokeRecorder);
    }
}
