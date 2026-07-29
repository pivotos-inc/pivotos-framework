package com.pivotos.message.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * message 域错误码（3xxx 段）
 *
 * <p>号段分配：
 * <ul>
 *   <li>3000-3019 消息</li>
 *   <li>3020-3039 模板</li>
 *   <li>3040-3059 发送渠道</li>
 * </ul>
 */
public enum MessageErrorCode implements ErrorCode {

    // ---------- 消息 ----------
    MESSAGE_NOT_FOUND(3001, "消息不存在"),
    USER_MESSAGE_NOT_FOUND(3002, "用户消息不存在"),
    MESSAGE_RECEIVER_EMPTY(3003, "消息接收人不能为空"),

    // ---------- 模板 ----------
    TEMPLATE_NOT_FOUND(3020, "消息模板不存在"),
    TEMPLATE_CODE_EXISTS(3021, "模板编码已存在"),
    TEMPLATE_DISABLED(3022, "消息模板已停用"),

    // ---------- 发送渠道 ----------
    CHANNEL_UNSUPPORTED(3040, "不支持的消息发送渠道"),
    CHANNEL_SEND_FAILED(3041, "消息渠道发送失败");

    private final int code;
    private final String msg;

    MessageErrorCode(int code, String msg) {
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
