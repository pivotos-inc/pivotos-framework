package com.pivotos.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 会话重命名请求（对齐 ai_conversation.title varchar(128)） */
@Data
public class ConversationRenameRequest {

    /** 会话标题 */
    @NotBlank(message = "会话标题不能为空")
    @Size(max = 128, message = "会话标题最长 128 字符")
    private String title;
}
