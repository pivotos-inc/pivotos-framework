package com.pivotos.ai.config;

import com.pivotos.ai.tool.WorkflowQueryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 工具回调装配（S97 A2 前置验证）。
 *
 * <p>MethodToolCallbackProvider 扫描工具对象的 @Tool 方法生成 ToolCallback，
 * 一份产物两条消费链路：
 * <ul>
 *   <li>MCP Server 自动配置（spring-ai-starter-mcp-server-webmvc）拾取容器内
 *       全部 ToolCallbackProvider，经 SSE 端点暴露 tools/list、tools/call；</li>
 *   <li>AiChatServiceImpl 同步对话链路注入本 Bean 挂载 toolCallbacks，
 *       模型可发起 function calling（「AI 查当前用户待办数」Hello World）。</li>
 * </ul>
 *
 * <p>S98 起工具注册表（ai_tool + 角色白名单 + 审计）在此装配点之上建实，
 * 本配置保持「单点汇聚全部工具」的唯一入口语义。
 */
@Configuration(proxyBeanMethods = false)
public class AiToolCallbackConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiToolCallbackConfiguration.class);

    @Bean
    public ToolCallbackProvider pivotToolCallbackProvider(WorkflowQueryTools workflowQueryTools) {
        log.info("[PivotOS] AI 工具回调装配：WorkflowQueryTools（MCP 端点 + 对话 tools 双消费）");
        return MethodToolCallbackProvider.builder()
                .toolObjects(workflowQueryTools)
                .build();
    }
}
