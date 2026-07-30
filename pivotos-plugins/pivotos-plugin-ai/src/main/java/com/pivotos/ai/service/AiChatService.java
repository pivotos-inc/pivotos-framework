package com.pivotos.ai.service;

import com.pivotos.ai.domain.dto.ChatSendRequest;
import com.pivotos.ai.domain.vo.ChatMessageVO;
import com.pivotos.ai.domain.vo.ConversationVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 对话服务（会话管理 + 同步/流式对话）
 *
 * <p>所有方法按 userId 做归属校验，越权访问一律 5001（不泄露资源存在性）。
 */
public interface AiChatService {

    /**
     * 同步对话：落库用户消息 → 调模型（阻塞）→ 落库并返回助手消息
     *
     * @param userId  当前登录用户
     * @param request 对话请求（conversationId 为空则新建会话）
     * @return 助手回复消息（含 conversationId，前端据此定位新会话）
     */
    ChatMessageVO send(Long userId, ChatSendRequest request);

    /**
     * 流式对话（SSE）：事件序列 meta → delta* → done，异常时 error
     *
     * @param userId  当前登录用户
     * @param request 对话请求（conversationId 为空则新建会话）
     * @return SseEmitter（无超时，由模型流结束或异常驱动完成）
     */
    SseEmitter stream(Long userId, ChatSendRequest request);

    /** 我的会话列表（按最近活跃倒序） */
    List<ConversationVO> listConversations(Long userId);

    /** 会话内消息历史（按时间正序） */
    List<ChatMessageVO> listMessages(Long userId, Long conversationId);

    /** 删除会话（连带其全部消息，逻辑删除） */
    void deleteConversation(Long userId, Long conversationId);
}
