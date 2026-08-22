package com.pivotos.ai.tool;

/**
 * 工具调用被守卫层拒绝（S98 A2）：未注册/停用/越权/未二次确认。
 *
 * <p>不继承 ServiceException 的理由：本异常只被 GuardedToolCallback 捕获并
 * 转成「给模型/调用方看的文本结果 + forbidden 审计」，不走全局异常处理器响应体。
 */
public class ToolForbiddenException extends RuntimeException {

    public ToolForbiddenException(String message) {
        super(message);
    }
}
