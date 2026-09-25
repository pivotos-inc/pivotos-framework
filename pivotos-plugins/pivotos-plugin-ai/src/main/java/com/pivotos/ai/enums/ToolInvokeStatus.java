package com.pivotos.ai.enums;

/**
 * AI 工具调用状态（S98 A2，ai_tool_invoke.invoke_status）
 */
public enum ToolInvokeStatus {

    /** 执行成功 */
    SUCCESS("success"),

    /** 执行失败（工具内部异常） */
    FAIL("fail"),

    /** 越权/停用拒绝（未执行） */
    FORBIDDEN("forbidden"),

    /** 写操作未带 confirm=true，仅返回预检结果（未执行） */
    NEED_CONFIRM("need_confirm");

    private final String code;

    ToolInvokeStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
