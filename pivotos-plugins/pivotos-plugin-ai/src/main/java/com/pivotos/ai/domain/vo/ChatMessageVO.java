package com.pivotos.ai.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** AI 对话消息 VO（会话历史） */
@Data
public class ChatMessageVO {

    /** 消息ID */
    private Long id;

    /** 会话ID */
    private Long conversationId;

    /** 角色（user/assistant） */
    private String role;

    /** 消息内容 */
    private String content;

    /** RAG 引用来源（仅 assistant 消息且使用了知识库时有值） */
    private List<ChatReferenceVO> references;

    /** 创建时间 */
    private LocalDateTime createTime;
}
