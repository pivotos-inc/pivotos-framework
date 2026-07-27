package com.pivotos.message.api.enums;

/**
 * 消息发送渠道
 */
public enum MessageChannelEnum {

    /** 站内信（落库 msg_message + msg_user_message） */
    INBOX("inbox"),

    /** 短信（记录 msg_send_log，网关接入后真正下发） */
    SMS("sms"),

    /** 邮件（记录 msg_send_log，网关接入后真正下发） */
    EMAIL("email");

    private final String code;

    MessageChannelEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 按编码解析，未知编码返回 null */
    public static MessageChannelEnum of(String code) {
        for (MessageChannelEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
