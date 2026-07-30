package com.pivotos.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 对话请求（conversationId 为空 = 新建会话） */
@Data
public class ChatSendRequest {

    /** 会话ID（空则自动新建会话） */
    private Long conversationId;

    /** 用户消息内容 */
    @NotBlank(message = "对话内容不能为空")
    @Size(max = 8000, message = "对话内容过长")
    private String content;
}
