package com.pivotos.ai.config;

import com.pivotos.ai.service.AiToolGuardService;
import com.pivotos.ai.service.AiToolInvokeRecorder;
import com.pivotos.ai.tool.GuardedToolCallbackProvider;
import com.pivotos.ai.tool.MessageTools;
import com.pivotos.ai.tool.ToolObjectContributor;
import com.pivotos.ai.tool.WorkflowQueryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>下游插件（如 ai-coding 的工程文件读写工具）通过 {@link ToolObjectContributor}
 * 扩展点登记工具对象——架构边界禁止 plugin-ai 反向依赖其它 Plugin 实现，
 * 由下游主动上报既能保持依赖方向合法，也让它们共享同一套守卫与审计。
 */
@Configuration(proxyBeanMethods = false)
public class AiToolCallbackConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiToolCallbackConfiguration.class);

    @Bean
    public ToolCallbackProvider pivotToolCallbackProvider(WorkflowQueryTools workflowQueryTools,
                                                          MessageTools messageTools,
                                                          AiToolGuardService guardService,
                                                          AiToolInvokeRecorder invokeRecorder,
                                                          ObjectProvider<List<ToolObjectContributor>> contributors) {
        List<Object> toolObjects = new ArrayList<>();
        toolObjects.add(workflowQueryTools);
        toolObjects.add(messageTools);
        List<ToolObjectContributor> contributed = contributors.getIfAvailable();
        if (contributed != null) {
            for (ToolObjectContributor contributor : contributed) {
                Object[] objects = contributor.toolObjects();
                if (objects == null) {
                    continue;
                }
                for (Object object : objects) {
                    if (object != null) {
                        toolObjects.add(object);
                    }
                }
            }
        }
        log.info("[PivotOS] AI 工具回调装配：内置 {} 个 + 扩展登记 {} 个（守卫式：白名单 + 二次确认 + 审计）",
                2, toolObjects.size() - 2);
        ToolCallbackProvider raw = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build();
        return new GuardedToolCallbackProvider(raw, guardService, invokeRecorder);
    }
}
