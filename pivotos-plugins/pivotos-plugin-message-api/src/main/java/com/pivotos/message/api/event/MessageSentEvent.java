package com.pivotos.message.api.event;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息发送完成事件
 *
 * <p>纯 POJO（不依赖 Spring），单体形态由 message 插件经 Spring Event 发布；
 * 微服务形态可平移到 MQ，消费方（如移动端推送 Starter）只依赖本契约。
 */
public class MessageSentEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消息 ID */
    private final Long messageId;

    /** 标题 */
    private final String title;

    /** 消息类型（1 通知 2 公告 3 待办） */
    private final Integer msgType;

    /** 渠道（inbox / sms / email） */
    private final String channel;

    /** 接收人用户 ID 集合 */
    private final List<Long> receiverIds;

    /** 发送时间 */
    private final LocalDateTime sentTime;

    public MessageSentEvent(Long messageId, String title, Integer msgType, String channel,
                            List<Long> receiverIds, LocalDateTime sentTime) {
        this.messageId = messageId;
        this.title = title;
        this.msgType = msgType;
        this.channel = channel;
        this.receiverIds = List.copyOf(receiverIds);
        this.sentTime = sentTime;
    }

    public Long getMessageId() {
        return messageId;
    }

    public String getTitle() {
        return title;
    }

    public Integer getMsgType() {
        return msgType;
    }

    public String getChannel() {
        return channel;
    }

    public List<Long> getReceiverIds() {
        return receiverIds;
    }

    public LocalDateTime getSentTime() {
        return sentTime;
    }
}
