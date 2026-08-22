package com.pivotos.ai.service;

import com.pivotos.ai.enums.ToolInvokeStatus;

/**
 * AI 工具调用审计记录器（S98 A2）
 *
 * <p>全量留痕口径：成功/失败/越权拒绝/预检拦截每次调用落一条 ai_tool_invoke，
 * 落库失败仅记 WARN 不阻断工具链路（审计是旁路，不是业务门禁）。
 */
public interface AiToolInvokeRecorder {

    /**
     * 记录一次工具调用
     *
     * @param toolName 工具名
     * @param args     入参原文（JSON，内部截断至 1000 字符）
     * @param status   调用状态
     * @param errorMsg 失败/拒绝原因（成功传 null，内部截断至 500 字符）
     * @param costMs   执行耗时（毫秒）
     */
    void record(String toolName, String args, ToolInvokeStatus status, String errorMsg, long costMs);
}
