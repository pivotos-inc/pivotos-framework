package com.pivotos.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 对话请求（conversationId 为空 = 新建会话；providerId/model 为空 = 默认供应商与其默认模型） */
@Data
public class ChatSendRequest {

    /** 会话ID（空则自动新建会话） */
    private Long conversationId;

    /** 供应商ID（空则取启用中排序最靠前的供应商，均无则回落静态配置 ChatClient） */
    private Long providerId;

    /** 模型标识（空则取供应商默认模型） */
    @Size(max = 64, message = "模型标识最长 64 字符")
    private String model;

    /** 用户消息内容 */
    @NotBlank(message = "对话内容不能为空")
    @Size(max = 8000, message = "对话内容过长")
    private String content;

    /** RAG：对话关联的知识库 ID 列表（空 = 不使用知识库；非空则检索后将上下文注入 prompt） */
    @Size(max = 10, message = "最多关联 10 个知识库")
    private List<Long> kbIds;
}
