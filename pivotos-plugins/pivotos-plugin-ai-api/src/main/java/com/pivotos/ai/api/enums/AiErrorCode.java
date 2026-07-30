package com.pivotos.ai.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * ai 域错误码（5xxx 段）
 *
 * <p>号段分配：
 * <ul>
 *   <li>5000-5019 对话</li>
 *   <li>5020-5039 模型配置</li>
 *   <li>5040-5059 知识库/RAG（S20 预留）</li>
 *   <li>5060-5079 Agent/MCP/Skills（后续预留）</li>
 * </ul>
 */
public enum AiErrorCode implements ErrorCode {

    // ---------- 对话 ----------
    CONVERSATION_NOT_FOUND(5001, "会话不存在"),
    CHAT_CONTENT_EMPTY(5002, "对话内容不能为空"),
    CHAT_FAILED(5003, "AI 对话失败，请稍后重试"),

    // ---------- 模型配置 ----------
    AI_NOT_CONFIGURED(5020, "AI 模型未配置");

    private final int code;
    private final String msg;

    AiErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
