package com.pivotos.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.domain.dto.ChatSendRequest;
import com.pivotos.ai.domain.entity.AiChatMessage;
import com.pivotos.ai.domain.entity.AiConversation;
import com.pivotos.ai.domain.vo.ChatMessageVO;
import com.pivotos.ai.domain.vo.ConversationVO;
import com.pivotos.ai.mapper.AiChatMessageMapper;
import com.pivotos.ai.mapper.AiConversationMapper;
import com.pivotos.ai.service.AiChatService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话服务实现
 *
 * <p>模型调用经 starter-ai 装配的 ChatClient；未配置 api-key 时 ChatClient 不存在，
 * 端点返回 5020 而非启动失败。流式对话用 Spring AI 的 Flux 桥接 SseEmitter
 * （回调线程由 Reactor/HTTP 客户端调度，非裸线程，不触 A6）；回调线程 LoginContext
 * 已丢失，落库前显式补齐 userId/tenantId/审计时间，绕开自动填充对上下文的依赖。
 */
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    /** 会话标题截取长度（取首条用户消息前 20 字） */
    private static final int TITLE_MAX_LENGTH = 20;

    private final AiConversationMapper conversationMapper;
    private final AiChatMessageMapper chatMessageMapper;
    /** ChatClient 可能不存在（api-key 未配置），懒获取 + 5020 兜底 */
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final AiProperties aiProperties;
    private final Environment environment;

    @Override
    public ChatMessageVO send(Long userId, ChatSendRequest request) {
        ChatClient chatClient = requireChatClient();
        AiConversation conversation = resolveConversation(userId, request);
        List<Message> history = loadHistory(conversation.getId());
        saveMessage(conversation, userId, "user", request.getContent());

        String reply;
        try {
            reply = chatClient.prompt()
                    .messages(history)
                    .user(request.getContent())
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[PivotOS] AI 同步对话失败：conversationId={}", conversation.getId(), e);
            throw new ServiceException(AiErrorCode.CHAT_FAILED);
        }

        AiChatMessage assistant = saveMessage(conversation, userId, "assistant", reply);
        touchConversation(conversation.getId());
        return toMessageVO(assistant);
    }

    @Override
    public SseEmitter stream(Long userId, ChatSendRequest request) {
        ChatClient chatClient = requireChatClient();
        AiConversation conversation = resolveConversation(userId, request);
        List<Message> history = loadHistory(conversation.getId());
        AiChatMessage userMessage = saveMessage(conversation, userId, "user", request.getContent());

        // 0 = 不超时：长回复由模型流结束或异常驱动完成
        SseEmitter emitter = new SseEmitter(0L);
        sendEvent(emitter, "meta", Map.of(
                "conversationId", conversation.getId(),
                "userMessageId", userMessage.getId(),
                "title", conversation.getTitle()));

        Long conversationId = conversation.getId();
        StringBuilder answer = new StringBuilder();
        Flux<String> flux = chatClient.prompt()
                .messages(history)
                .user(request.getContent())
                .stream()
                .content();
        flux.subscribe(
                delta -> {
                    answer.append(delta);
                    sendEvent(emitter, "delta", Map.of("content", delta));
                },
                error -> {
                    log.error("[PivotOS] AI 流式对话失败：conversationId={}", conversationId, error);
                    sendEvent(emitter, "error", Map.of(
                            "code", AiErrorCode.CHAT_FAILED.getCode(),
                            "msg", AiErrorCode.CHAT_FAILED.getMsg()));
                    emitter.complete();
                },
                () -> {
                    // 回调线程无 LoginContext，saveMessage 内已显式补齐审计字段
                    AiChatMessage assistant = saveMessage(conversation, userId, "assistant", answer.toString());
                    touchConversation(conversationId);
                    sendEvent(emitter, "done", Map.of(
                            "conversationId", conversationId,
                            "messageId", assistant.getId()));
                    emitter.complete();
                });
        return emitter;
    }

    @Override
    public List<ConversationVO> listConversations(Long userId) {
        return conversationMapper.selectList(Wrappers.<AiConversation>lambdaQuery()
                        .eq(AiConversation::getUserId, userId)
                        .orderByDesc(AiConversation::getUpdateTime)
                        .orderByDesc(AiConversation::getId))
                .stream().map(this::toConversationVO).toList();
    }

    @Override
    public List<ChatMessageVO> listMessages(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
        return chatMessageMapper.selectList(Wrappers.<AiChatMessage>lambdaQuery()
                        .eq(AiChatMessage::getConversationId, conversationId)
                        .orderByAsc(AiChatMessage::getId))
                .stream().map(this::toMessageVO).toList();
    }

    @Override
    public void deleteConversation(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
        conversationMapper.deleteById(conversationId);
        chatMessageMapper.delete(Wrappers.<AiChatMessage>lambdaQuery()
                .eq(AiChatMessage::getConversationId, conversationId));
    }

    /** ChatClient 兜底：api-key 未配置时返回 5020 明确提示 */
    private ChatClient requireChatClient() {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        return chatClient;
    }

    /** 定位或新建会话（新建时标题取首条消息前 20 字，模型取 Spring AI 标准配置） */
    private AiConversation resolveConversation(Long userId, ChatSendRequest request) {
        if (request.getConversationId() != null) {
            return requireOwned(userId, request.getConversationId());
        }
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        String content = request.getContent().strip();
        conversation.setTitle(content.length() > TITLE_MAX_LENGTH
                ? content.substring(0, TITLE_MAX_LENGTH) : content);
        conversation.setModel(environment.getProperty("spring.ai.openai.chat.options.model", ""));
        conversationMapper.insert(conversation);
        return conversation;
    }

    /** 归属校验：不存在或非本人一律 5001（不泄露资源存在性） */
    private AiConversation requireOwned(Long userId, Long conversationId) {
        AiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new ServiceException(AiErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    /** 加载最近 maxHistory 条历史消息（时间正序），转 Spring AI Message */
    private List<Message> loadHistory(Long conversationId) {
        Page<AiChatMessage> page = chatMessageMapper.selectPage(
                new Page<>(1, aiProperties.getMaxHistory(), false),
                Wrappers.<AiChatMessage>lambdaQuery()
                        .eq(AiChatMessage::getConversationId, conversationId)
                        .orderByDesc(AiChatMessage::getId));
        List<AiChatMessage> rows = new ArrayList<>(page.getRecords());
        Collections.reverse(rows);
        return rows.stream()
                .<Message>map(row -> "assistant".equals(row.getRole())
                        ? new AssistantMessage(row.getContent())
                        : new UserMessage(row.getContent()))
                .toList();
    }

    /** 消息落库：显式补齐审计字段（流式回调线程 LoginContext 丢失，自动填充只在字段为 null 时兜底） */
    private AiChatMessage saveMessage(AiConversation conversation, Long userId, String role, String content) {
        LocalDateTime now = LocalDateTime.now();
        AiChatMessage message = new AiChatMessage();
        message.setConversationId(conversation.getId());
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setTenantId(conversation.getTenantId());
        message.setCreateBy(userId);
        message.setUpdateBy(userId);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        chatMessageMapper.insert(message);
        return message;
    }

    /** 刷新会话活跃时间（仅触发 update_time 自动填充） */
    private void touchConversation(Long conversationId) {
        AiConversation touch = new AiConversation();
        touch.setId(conversationId);
        touch.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(touch);
    }

    /** SSE 事件下发：data 走 JSON 转换器（换行安全），IO 异常说明客户端已断开 */
    private void sendEvent(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(new LinkedHashMap<>(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("[PivotOS] SSE 客户端已断开，事件 {} 丢弃", name);
        }
    }

    private ConversationVO toConversationVO(AiConversation conversation) {
        ConversationVO vo = new ConversationVO();
        vo.setId(conversation.getId());
        vo.setTitle(conversation.getTitle());
        vo.setModel(conversation.getModel());
        vo.setCreateTime(conversation.getCreateTime());
        vo.setUpdateTime(conversation.getUpdateTime());
        return vo;
    }

    private ChatMessageVO toMessageVO(AiChatMessage message) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(message.getId());
        vo.setConversationId(message.getConversationId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }
}
