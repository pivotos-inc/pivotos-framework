package com.pivotos.message.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 用户消息视图对象（"我收到的消息"） */
@Data
public class UserMessageVO {

    /** 用户消息ID（已读操作主键） */
    private Long id;

    /** 消息ID */
    private Long messageId;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 消息类型（1通知 2公告 3待办） */
    private Integer msgType;

    /** 业务类型 */
    private String bizType;

    /** 业务ID */
    private String bizId;

    /** 已读状态（0未读 1已读） */
    private Integer readStatus;

    /** 阅读时间 */
    private LocalDateTime readTime;

    /** 送达时间（用户消息创建时间） */
    private LocalDateTime createTime;
}
