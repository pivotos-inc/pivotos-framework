package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** AI 对话消息实体（会话内的单条 user/assistant 消息） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_chat_message")
public class AiChatMessage extends BaseDO {

    /** 会话ID */
    private Long conversationId;

    /** 归属用户ID（冗余，越权校验免联表） */
    private Long userId;

    /** 角色（user / assistant） */
    private String role;

    /** 消息内容 */
    private String content;

    /** 租户ID（多租户预留，与 msg_ 表同款约定） */
    private Long tenantId;
}
