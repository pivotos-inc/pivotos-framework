package com.pivotos.ai.orchestrator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.tool.AiToolMeta;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排侧工具目录（A5-1 / S116）：把「活工具回调」翻译成计划校验与提示词需要的形态。
 *
 * <p>两条基准都必须取自运政态（写代码时定死，防漂移）：
 * <ol>
 *   <li><b>能用什么工具</b> = 容器内 {@code ToolCallbackProvider} 的活回调集合——
 *       与 MCP {@code tools/list}、对话链路 function calling 同源，三者看到的世界必须一致；</li>
 *   <li><b>参数怎么填</b> = Spring AI 生成的 {@code ToolDefinition.inputSchema()} JSON Schema——
 *       不再手写一份平行参数表，工具类改签名时校验器立刻跟上（javap 实证：
 *       Spring AI 2.0 的 {@code ToolDefinition} 提供 {@code inputSchema()}）。</li>
 * </ol>
 *
 * @param name        工具名
 * @param description 工具描述（喂给 LLM）
 * @param properties  参数名 → JSON Schema 类型（string / integer / boolean / number）
 * @param required    必填参数名（来自 schema 的 required 数组）
 * @param write       是否写操作（写操作受 A2 二次确认协议约束，计划期不得代为确认）
 */
public record ToolSpec(String name, String description, Map<String, String> properties,
                       List<String> required, boolean write) {

    /**
     * 从活工具回调构建目录条目；{@code meta} 缺失时按只读口径（从严校验反而由写标记决定，
     * 这里即便误判为只读，执行期仍有 {@code GuardedToolCallback} 的 confirm 闸兜底）。
     */
    public static ToolSpec of(ToolCallback callback, AiToolMeta meta) {
        ToolDefinition definition = callback.getToolDefinition();
        String schema = definition.inputSchema();
        Map<String, String> properties = new LinkedHashMap<>();
        List<String> required = List.of();
        if (schema != null && !schema.isBlank()) {
            try {
                JSONObject root = JSON.parseObject(schema);
                if (root != null) {
                    JSONObject props = root.getJSONObject("properties");
                    if (props != null) {
                        for (String key : props.keySet()) {
                            JSONObject property = props.getJSONObject(key);
                            properties.put(key, property == null ? "string" : property.getString("type"));
                        }
                    }
                    required = root.getJSONArray("required") == null
                            ? List.of()
                            : root.getJSONArray("required").toList(String.class);
                }
            } catch (Exception e) {
                // Schema 解析失败不致命：退化成「只校验工具名」的宽松口径，
                // 参数级闸门在执行期仍由工具自身的 JSON Schema 兜住
                properties = new LinkedHashMap<>();
            }
        }
        return new ToolSpec(definition.name(), definition.description(),
                properties, required, meta != null && meta.confirmRequired());
    }
}
