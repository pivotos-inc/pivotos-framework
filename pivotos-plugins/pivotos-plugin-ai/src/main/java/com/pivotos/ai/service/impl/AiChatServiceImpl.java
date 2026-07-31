package com.pivotos.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.client.AiClientRegistry;
import com.pivotos.ai.domain.dto.ChatSendRequest;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiChatMessage;
import com.pivotos.ai.domain.entity.AiConversation;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.domain.vo.ChatMessageVO;
import com.pivotos.ai.domain.vo.ConversationVO;
import com.pivotos.ai.mapper.AiChatMessageMapper;
import com.pivotos.ai.mapper.AiConversationMapper;
import com.pivotos.ai.service.AiChatService;
import com.pivotos.ai.service.AiProviderService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
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
 * <p>模型调用优先走后台配置的动态供应商（AiClientRegistry 按供应商×Key 缓存 ChatClient，
 * 多 Key 轮询分摊，同步调用失败自动换下一 Key 重试一次；流式已发 meta 不重试）；
 * 未指定供应商且无默认供应商时回落 starter-ai 装配的静态 ChatClient（spring.ai.openai.*），
 * 再无则端点返回 5020 而非启动失败。流式对话用 Spring AI 的 Flux 桥接 SseEmitter
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
    /** 静态兜底 ChatClient（spring.ai.openai.* 未配置时不存在），懒获取 + 5020 兜底 */
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final AiProperties aiProperties;
    private final Environment environment;
    private final AiProviderService aiProviderService;
    private final AiClientRegistry clientRegistry;

    @Override
    public ChatMessageVO send(Long userId, ChatSendRequest request) {
        ChatTarget target = resolveTarget(request);
        AiConversation conversation = resolveConversation(userId, request, target.model());
        List<Message> history = loadHistory(conversation.getId());
        saveMessage(conversation, userId, "user", request.getContent());

        String reply = callWithFailover(target, history, request.getContent(), conversation.getId());

        AiChatMessage assistant = saveMessage(conversation, userId, "assistant", reply);
        touchConversation(conversation.getId());
        return toMessageVO(assistant);
    }

    @Override
    public SseEmitter stream(Long userId, ChatSendRequest request) {
        ChatTarget target = resolveTarget(request);
        // 流式只用轮询起点 Key（不重试），回调里据此记健康度；静态目标无 Key 不记
        AiApiKey streamKey = pickKey(target, 0);
        ChatClient chatClient = pickClient(target, 0);
        AiConversation conversation = resolveConversation(userId, request, target.model());
        List<Message> history = loadHistory(conversation.getId());
        AiChatMessage userMessage = saveMessage(conversation, userId, "user", request.getContent());

        // 0 = 不超时：长回复由模型流结束或异常驱动完成
        SseEmitter emitter = new SseEmitter(0L);
        sendEvent(emitter, "meta", Map.of(
                "conversationId", conversation.getId(),
                "userMessageId", userMessage.getId(),
                "title", conversation.getTitle()));

        Long conversationId = conversation.getId();
        if (streamKey != null) {
            // 轮询分摊可审计：每次调用记录实际使用的 keyId
            log.info("[PivotOS] AI 流式对话使用 Key：conversationId={} providerId={} keyId={}",
                    conversationId, target.provider().getId(), streamKey.getId());
        }
        StringBuilder answer = new StringBuilder();
        // 流式已发 meta，失败不换 Key 重试（半途换 Key 会重复输出），直接下发 error 事件
        Flux<String> flux = buildPrompt(chatClient, target, history, request.getContent())
                .stream()
                .content();
        flux.subscribe(
                delta -> {
                    answer.append(delta);
                    sendEvent(emitter, "delta", Map.of("content", delta));
                },
                error -> {
                    log.error("[PivotOS] AI 流式对话失败：conversationId={} keyId={}",
                            conversationId, streamKey == null ? null : streamKey.getId(), error);
                    if (streamKey != null) {
                        aiProviderService.recordKeyFailure(streamKey.getId());
                    }
                    sendEvent(emitter, "error", Map.of(
                            "code", AiErrorCode.CHAT_FAILED.getCode(),
                            "msg", AiErrorCode.CHAT_FAILED.getMsg()));
                    emitter.complete();
                },
                () -> {
                    if (streamKey != null) {
                        aiProviderService.recordKeySuccess(streamKey.getId());
                    }
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

    /**
     * 解析调用目标（三级兜底链）：
     * ① 请求指定 providerId → 校验启用 + 有可用 Key；
     * ② 未指定 → 默认供应商（启用中 sort 最靠前且有启用 Key 者）；
     * ③ 无动态供应商 → 静态 ChatClient（spring.ai.openai.*）；再无 → 5020。
     */
    private ChatTarget resolveTarget(ChatSendRequest request) {
        AiProvider provider = request.getProviderId() != null
                ? aiProviderService.requireActiveProvider(request.getProviderId())
                : aiProviderService.findDefaultProvider();
        if (provider != null) {
            List<AiApiKey> keys = aiProviderService.listActiveKeys(provider.getId());
            if (keys.isEmpty()) {
                throw new ServiceException(AiErrorCode.NO_AVAILABLE_KEY);
            }
            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel().strip()
                    : provider.getDefaultModel();
            int startIndex = clientRegistry.nextKeyIndex(provider.getId(), keys.size());
            return new ChatTarget(provider, keys, startIndex, model, null);
        }
        ChatClient staticClient = chatClientProvider.getIfAvailable();
        if (staticClient == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        return new ChatTarget(null, null, 0,
                environment.getProperty("spring.ai.openai.chat.options.model", ""), staticClient);
    }

    /** 取第 attempt 次尝试对应的 Key（轮询偏移取模；静态目标无 Key 返回 null） */
    private AiApiKey pickKey(ChatTarget target, int attempt) {
        if (!target.dynamic()) {
            return null;
        }
        return target.keys().get((target.startIndex() + attempt) % target.keys().size());
    }

    /** 取第 attempt 次尝试对应的 ChatClient（动态走轮询偏移，静态恒为兜底 client） */
    private ChatClient pickClient(ChatTarget target, int attempt) {
        AiApiKey key = pickKey(target, attempt);
        if (key == null) {
            return target.staticClient();
        }
        return clientRegistry.getChatClient(target.provider(), key);
    }

    /** 组装 prompt：动态目标按请求/供应商模型覆盖 options（静态 client 用其自带默认模型） */
    private ChatClient.ChatClientRequestSpec buildPrompt(
            ChatClient client, ChatTarget target, List<Message> history, String content) {
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .messages(history)
                .user(content);
        if (target.dynamic() && target.model() != null && !target.model().isBlank()) {
            // Spring AI 2.0 options() 收 Builder 本体，内部与 client 默认 options 合并
            spec = spec.options(OpenAiChatOptions.builder().model(target.model()));
        }
        return spec;
    }

    /** 同步调用：动态目标失败自动换下一 Key 重试一次（单 Key 或静态目标不重试），逐 Key 记健康度 */
    private String callWithFailover(ChatTarget target, List<Message> history, String content, Long conversationId) {
        int attempts = target.dynamic() ? Math.min(2, target.keys().size()) : 1;
        for (int i = 0; i < attempts; i++) {
            AiApiKey key = pickKey(target, i);
            if (key != null) {
                // 轮询分摊可审计：每次尝试记录实际使用的 keyId
                log.info("[PivotOS] AI 同步对话使用 Key：conversationId={} providerId={} keyId={} 第 {}/{} 次尝试",
                        conversationId, target.provider().getId(), key.getId(), i + 1, attempts);
            }
            try {
                String reply = buildPrompt(pickClient(target, i), target, history, content)
                        .call()
                        .content();
                if (key != null) {
                    aiProviderService.recordKeySuccess(key.getId());
                }
                return reply;
            } catch (Exception e) {
                boolean lastAttempt = i == attempts - 1;
                log.error("[PivotOS] AI 同步对话失败：conversationId={} providerId={} keyId={} 第 {}/{} 次尝试",
                        conversationId, target.dynamic() ? target.provider().getId() : null,
                        key == null ? null : key.getId(), i + 1, attempts, e);
                if (key != null) {
                    aiProviderService.recordKeyFailure(key.getId());
                }
                if (lastAttempt) {
                    throw new ServiceException(AiErrorCode.CHAT_FAILED);
                }
            }
        }
        throw new ServiceException(AiErrorCode.CHAT_FAILED);
    }

    /** 调用目标：动态 = 供应商 + 启用 Key 列表 + 轮询起点；静态 = 兜底 ChatClient */
    private record ChatTarget(AiProvider provider, List<AiApiKey> keys, int startIndex,
                              String model, ChatClient staticClient) {
        boolean dynamic() {
            return provider != null;
        }
    }

    /** 定位或新建会话（新建时标题取首条消息前 20 字，模型记本次实际解析结果） */
    private AiConversation resolveConversation(Long userId, ChatSendRequest request, String model) {
        if (request.getConversationId() != null) {
            return requireOwned(userId, request.getConversationId());
        }
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        String content = request.getContent().strip();
        conversation.setTitle(content.length() > TITLE_MAX_LENGTH
                ? content.substring(0, TITLE_MAX_LENGTH) : content);
        conversation.setModel(model == null ? "" : model);
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
