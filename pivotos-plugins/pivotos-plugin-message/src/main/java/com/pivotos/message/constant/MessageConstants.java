package com.pivotos.message.constant;

/**
 * message 插件常量
 */
public final class MessageConstants {

    private MessageConstants() {
    }

    /** 消息类型：通知 */
    public static final int TYPE_NOTICE = 1;

    /** 消息类型：公告 */
    public static final int TYPE_ANNOUNCEMENT = 2;

    /** 消息类型：待办 */
    public static final int TYPE_TODO = 3;

    /** 未读 */
    public static final int READ_NO = 0;

    /** 已读 */
    public static final int READ_YES = 1;

    /** 状态：正常 */
    public static final int STATUS_NORMAL = 0;

    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;

    /** 发送状态：成功 */
    public static final int SEND_SUCCESS = 0;

    /** 发送状态：失败 */
    public static final int SEND_FAILED = 1;
}
