package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** AI 会话实体（用户 × 多轮对话的容器） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_conversation")
public class AiConversation extends BaseDO {

    /** 归属用户ID */
    private Long userId;

    /** 会话标题（默认取首条用户消息前 20 字） */
    private String title;

    /** 使用的模型标识（如 qwen-plus） */
    private String model;

    /** 租户ID（多租户预留，与 msg_ 表同款约定） */
    private Long tenantId;
}
