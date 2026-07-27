package com.pivotos.message.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 用户消息实体（消息 × 接收人的已读状态载体） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_user_message")
public class MsgUserMessage extends BaseDO {

    /** 消息ID */
    private Long messageId;

    /** 接收人用户ID */
    private Long userId;

    /** 已读状态（0未读 1已读） */
    private Integer readStatus;

    /** 阅读时间 */
    private LocalDateTime readTime;

    /** 租户ID（多租户预留，S14 生效，当前由代码置 null） */
    private Long tenantId;
}
