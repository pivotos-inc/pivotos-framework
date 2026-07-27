package com.pivotos.message.api.dto;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 消息传输对象（用户视角：一条"我收到的消息"）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MessageDTO extends BaseDTO {

    /** 用户消息 ID（msg_user_message 主键，已读操作以此为准） */
    private Long userMessageId;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 消息类型（1 通知 2 公告 3 待办） */
    private Integer msgType;

    /** 业务类型（来源模块自定义，如 flow.approval） */
    private String bizType;

    /** 业务 ID（跳转定位用） */
    private String bizId;

    /** 已读状态（0 未读 1 已读） */
    private Integer readStatus;

    /** 阅读时间 */
    private LocalDateTime readTime;
}
