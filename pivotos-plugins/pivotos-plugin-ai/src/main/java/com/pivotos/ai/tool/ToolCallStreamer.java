package com.pivotos.ai.tool;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.service.AiToolService;
import com.pivotos.common.core.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 工具流式调用编排（S118 AI-2 四入口·SSE 入口）。
 *
 * <p>把「工具的名字 + 入参」编排成一段 SSE 帧序列，帧协议与既有 SSE 端点
 * （AiChat / AiApprovalAdvice 的 meta → delta → done）保持同款：
 * <ul>
 *   <li>{@code meta}：工具名 + 发起时间戳（客户端据此展示「正在调用…」）；</li>
 *   <li>{@code result}：工具返回值（与 REST {@code /ai/tool/call} 的 data 同值同源）；</li>
 *   <li>{@code error}：失败原因（code + msg）；</li>
 *   <li>{@code done}：流结束标记（无论成败都会发，客户端据此收口）。</li>
 * </ul>
 *
 * <p>关键口径（两条都不能破）：
 * <ol>
 *   <li>**不得出现第二条守卫链**——本类只调用 {@link AiToolService#invokeTool}，
 *       注册闸 / 角色白名单 / 写操作二次确认 / 审计全部复用 {@link GuardedToolCallback}，
 *       因此 SSE 与 REST、MCP、ChatClient 四入口的放行判定与留痕**天然同源**；</li>
 *   <li>**异常必须落成 error 帧而不是抛出**——外抛会被全局异常兜底改写成 HTTP 5xx，
 *       SSE 客户端只会看到连接中断，拿不到结构化原因，也无法区分「工具没注册」与「工具执行失败」。</li>
 * </ol>
 *
 * <p>本类不碰 Web 类型（无 SseEmitter / HTTP 语义），Controller 只做「帧 → SSE 事件」的薄适配，
 * 因此帧序逻辑可单测（{@code ToolCallStreamerTest}）。
 */
public class ToolCallStreamer {

    /** SSE 单帧：事件名 + 载荷 */
    public record Frame(String name, Object data) {
    }

    /** 入参缺省时的空 JSON（与 REST 入口同口径） */
    private static final String EMPTY_ARGS = "{}";

    private final AiToolService aiToolService;

    public ToolCallStreamer(AiToolService aiToolService) {
        this.aiToolService = aiToolService;
    }

    /**
     * 执行工具并把帧序列推送给 sink。
     *
     * @param toolName 工具名（ai_tool.tool_name）
     * @param argsJson 入参 JSON（null/空白按 {} 处理）
     * @param sink     帧消费者
     */
    public void stream(String toolName, String argsJson, Consumer<Frame> sink) {
        sink.accept(new Frame("meta", metaData(toolName)));
        try {
            String result = aiToolService.invokeTool(toolName, normalize(argsJson));
            sink.accept(new Frame("result", result));
        } catch (ServiceException e) {
            sink.accept(new Frame("error", errorData(e.getCode(), e.getMessage())));
        } catch (Exception e) {
            // 非业务异常（IO / NPE 等）统一收敛为 5073，不落成本 HTTP 5xx
            sink.accept(new Frame("error", errorData(AiErrorCode.AI_TOOL_STREAM_FAILED.getCode(),
                    AiErrorCode.AI_TOOL_STREAM_FAILED.getMsg() + "：" + e.getMessage())));
        } finally {
            sink.accept(new Frame("done", metaData(toolName)));
        }
    }

    private String normalize(String argsJson) {
        return argsJson == null || argsJson.isBlank() ? EMPTY_ARGS : argsJson;
    }

    private Map<String, Object> metaData(String toolName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", toolName);
        data.put("timestamp", System.currentTimeMillis());
        return data;
    }

    private Map<String, Object> errorData(int code, String msg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("msg", msg);
        return data;
    }
}
