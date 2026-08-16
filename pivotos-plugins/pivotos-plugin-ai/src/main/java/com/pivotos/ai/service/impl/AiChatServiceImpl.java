package com.pivotos.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.api.usage.AiUsageContext;
import com.pivotos.ai.client.AiClientRegistry;
import com.pivotos.ai.domain.dto.ChatSendRequest;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiChatMessage;
import com.pivotos.ai.domain.entity.AiConversation;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.domain.vo.ChatMessageVO;
import com.pivotos.ai.domain.vo.ChatReferenceVO;
import com.pivotos.ai.domain.vo.ConversationVO;
import com.pivotos.ai.kb.api.dto.KbOptionDTO;
import com.pivotos.ai.kb.api.dto.KbSearchResultDTO;
import com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** 知识库门面（可选依赖：kb 插件未部署时为 null，RAG 功能静默降级） */
    private final ObjectProvider<IKnowledgeBaseFacade> kbFacadeProvider;

    @Override
    public ChatMessageVO send(Long userId, ChatSendRequest request) {
        // S92 用量场景标注：对话链路（内嵌 routeQuery 临时切 rag 场景）
        AiUsageContext.setScene(AiUsageContext.SCENE_CHAT);
        try {
            ChatTarget target = resolveTarget(request);
            AiConversation conversation = resolveConversation(userId, request, target.model());
            List<Message> history = loadHistory(conversation.getId());
            saveMessage(conversation, userId, "user", request.getContent(), null);

            // RAG：检索知识库并构建上下文（S68：含查询改写）
            RagContext rag = buildRagContext(request.getKbIds(), request.getContent(), history, target);

            String reply = callWithFailover(target, history, request.getContent(),
                    conversation.getId(), rag.context());

            AiChatMessage assistant = saveMessage(conversation, userId, "assistant", reply,
                    referencesJson(rag.references()));
            touchConversation(conversation.getId());
            ChatMessageVO vo = toMessageVO(assistant);
            vo.setReferences(rag.references().isEmpty() ? null : rag.references());
            return vo;
        } finally {
            AiUsageContext.clear();
        }
    }

    @Override
    public SseEmitter stream(Long userId, ChatSendRequest request) {
        // S92 用量场景标注：对话链路（流式）；计量快照在 flux 组装时于请求线程抓取
        AiUsageContext.setScene(AiUsageContext.SCENE_CHAT);
        try {
            ChatTarget target = resolveTarget(request);
            // 流式只用轮询起点 Key（不重试），回调里据此记健康度；静态目标无 Key 不记
            AiApiKey streamKey = pickKey(target, 0);
            ChatClient chatClient = pickClient(target, 0);
            AiConversation conversation = resolveConversation(userId, request, target.model());
            List<Message> history = loadHistory(conversation.getId());
            AiChatMessage userMessage = saveMessage(conversation, userId, "user", request.getContent(), null);

            // RAG：检索知识库并构建上下文（S68：含查询改写）
            RagContext rag = buildRagContext(request.getKbIds(), request.getContent(), history, target);

            // 0 = 不超时：长回复由模型流结束或异常驱动完成
            SseEmitter emitter = new SseEmitter(0L);
            Map<String, Object> metaData = new LinkedHashMap<>();
            metaData.put("conversationId", conversation.getId());
            metaData.put("userMessageId", userMessage.getId());
            metaData.put("title", conversation.getTitle());
            if (rag.rewrittenQuery() != null) {
                // S68：查询改写发生时的透明化提示（前端展示实际检索词）
                metaData.put("rewrittenQuery", rag.rewrittenQuery());
            }
            if (rag.kbRoutedOut()) {
                // S69：意图路由出局——本轮判定无需知识库检索（前端提示按通用知识回答）
                metaData.put("kbRoutedOut", true);
            }
            sendEvent(emitter, "meta", metaData);

            Long conversationId = conversation.getId();
            if (streamKey != null) {
                // 轮询分摊可审计：每次调用记录实际使用的 keyId
                log.info("[PivotOS] AI 流式对话使用 Key：conversationId={} providerId={} keyId={}",
                        conversationId, target.provider().getId(), streamKey.getId());
            }
            StringBuilder answer = new StringBuilder();
            // 流式已发 meta，失败不换 Key 重试（半途换 Key 会重复输出），直接下发 error 事件
            Flux<String> flux = buildPrompt(chatClient, target, history, request.getContent(), rag.context())
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
                        AiChatMessage assistant = saveMessage(conversation, userId, "assistant",
                                answer.toString(), referencesJson(rag.references()));
                        touchConversation(conversationId);
                        Map<String, Object> doneData = new LinkedHashMap<>();
                        doneData.put("conversationId", conversationId);
                        doneData.put("messageId", assistant.getId());
                        if (!rag.references().isEmpty()) {
                            doneData.put("references", rag.references());
                        }
                        sendEvent(emitter, "done", doneData);
                        emitter.complete();
                    });
            return emitter;
        } finally {
            AiUsageContext.clear();
        }
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

    @Override
    public void renameConversation(Long userId, Long conversationId, String title) {
        requireOwned(userId, conversationId);
        // 用 UpdateWrapper 只改 title：updateById 会触发审计填充刷新 update_time，
        // 而重命名不算活跃行为，不应让会话在「最近活跃倒序」列表里上浮
        conversationMapper.update(null, Wrappers.<AiConversation>lambdaUpdate()
                .eq(AiConversation::getId, conversationId)
                .set(AiConversation::getTitle, title.strip()));
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

    /**
     * 组装 prompt：动态目标按请求/供应商模型覆盖 options（按 provider.code 分派工厂产出；静态 client 用其自带默认模型）。
     * RAG 上下文非空时以 system 消息注入，位于历史消息之后、用户消息之前。
     */
    private ChatClient.ChatClientRequestSpec buildPrompt(
            ChatClient client, ChatTarget target, List<Message> history,
            String content, String ragContext) {
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .messages(history);
        if (ragContext != null) {
            spec = spec.system(ragContext);
        }
        spec = spec.user(content);
        if (target.dynamic() && target.model() != null && !target.model().isBlank()) {
            // Spring AI 2.0 options() 收 Builder 本体，内部与 client 默认 options 合并；
            // 工厂按 code 产出对应 SDK 的 options Builder 上转型，屏蔽供应商类型差异
            spec = spec.options(clientRegistry.buildChatOptions(target.provider(), target.model()));
        }
        return spec;
    }

    /** 同步调用：动态目标失败自动换下一 Key 重试一次（单 Key 或静态目标不重试），逐 Key 记健康度 */
    private String callWithFailover(ChatTarget target, List<Message> history, String content,
                                     Long conversationId, String ragContext) {
        int attempts = target.dynamic() ? Math.min(2, target.keys().size()) : 1;
        for (int i = 0; i < attempts; i++) {
            AiApiKey key = pickKey(target, i);
            if (key != null) {
                // 轮询分摊可审计：每次尝试记录实际使用的 keyId
                log.info("[PivotOS] AI 同步对话使用 Key：conversationId={} providerId={} keyId={} 第 {}/{} 次尝试",
                        conversationId, target.provider().getId(), key.getId(), i + 1, attempts);
            }
            try {
                String reply = buildPrompt(pickClient(target, i), target, history, content, ragContext)
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

    /* ================= RAG 知识库检索 ================= */
    
    /** RAG 上下文载体：检索上下文文本 + 引用来源列表 + 改写后的检索词（未改写为 null，S68）+ 意图路由出局标记（S69） */
    private record RagContext(String context, List<ChatReferenceVO> references, String rewrittenQuery, boolean kbRoutedOut) {}
    
    /** 意图路由决策：是否需要知识库检索 + 实际检索词（S69，路由与改写合并单次 LLM 调用） */
    private record RouteDecision(boolean needSearch, String query) {}
    
    /** RAG 上下文展示前 N 字（避免 prompt 过长） */
    private static final int REFERENCE_CONTENT_MAX = 200;
    /** RAG 每个知识库默认召回条数 */
    private static final int RAG_TOP_K = 5;
    /** 查询改写参考的最近历史消息条数（S68） */
    private static final int REWRITE_HISTORY_LIMIT = 4;
    /** 意图路由 + 查询改写 system 指令（S69）：严格只输出 JSON，不回答问题 */
    private static final String ROUTE_SYSTEM_PROMPT = """
            你是知识库检索路由助手。根据对话历史中的用户问题，对用户最新的问题做两个判断：\
            一、是否需要检索知识库：打招呼、闲聊、纯常识或计算类问题输出 needSearch=false，可能需要参考知识库内容的问题输出 needSearch=true；\
            二、把最新问题改写为可独立检索的查询语句（补全代词与省略的上下文，保持原意，不加无关内容）。\
            严禁回答问题，严禁输出任何解释，只输出一行 JSON：{"needSearch":true,"query":"..."}。\
            示例：最新问题「你好」→ {"needSearch":false,"query":"你好"}；\
            历史问题「鲫鱼汤怎么做才适合产妇喝？」，最新问题「那要炖多久呢？」→ {"needSearch":true,"query":"产妇喝的鲫鱼汤要炖多久"}""";
    
    /**
     * 构建 RAG 上下文：遍历 kbIds 逐个检索，拼接为 system prompt 格式的参考资料文本。
     * kbIds 为空或 kb 门面不可用时返回 null context + 空 references（静默降级）。
     * S69：检索前先做意图路由（与 S68 查询改写合并单次 LLM 调用），判定无需检索则跳过全部检索；
     * 引用携带 kbId/kbName/docId/chunkId 溯源信息。
     */
    private RagContext buildRagContext(List<Long> kbIds, String query, List<Message> history, ChatTarget target) {
        if (kbIds == null || kbIds.isEmpty()) {
            return new RagContext(null, Collections.emptyList(), null, false);
        }
        IKnowledgeBaseFacade facade = kbFacadeProvider.getIfAvailable();
        if (facade == null) {
            return new RagContext(null, Collections.emptyList(), null, false);
        }
    
        // 知识库选项映射（名称展示 + 改写开关判断）
        Map<Long, KbOptionDTO> optionMap = new LinkedHashMap<>();
        try {
            facade.listOptions().forEach(o -> optionMap.put(o.getId(), o));
        } catch (Exception e) {
            log.warn("[PivotOS] RAG 加载知识库选项失败，kbName/queryRewrite 降级: {}", e.getMessage());
        }
    
        // S69 意图路由 + S68 查询改写：合并单次 LLM 调用（异常降级为检索 + 原问题）
        boolean rewriteEnabled = kbIds.stream()
                .anyMatch(id -> optionMap.get(id) != null && Boolean.TRUE.equals(optionMap.get(id).getQueryRewrite()));
        RouteDecision decision = routeQuery(target, history, query, rewriteEnabled);
        if (!decision.needSearch()) {
            return new RagContext(null, Collections.emptyList(), null, true);
        }
        String searchQuery = decision.query();
        String rewrittenQuery = searchQuery.strip().equals(query.strip()) ? null : searchQuery;
    
        List<KbSearchResultDTO> allResults = new ArrayList<>();
        for (Long kbId : kbIds) {
            try {
                List<KbSearchResultDTO> results = facade.search(kbId, searchQuery, RAG_TOP_K);
                allResults.addAll(results);
            } catch (Exception e) {
                log.warn("[PivotOS] RAG 检索知识库失败，已跳过：kbId={}, error={}", kbId, e.getMessage());
            }
        }
        if (allResults.isEmpty()) {
            return new RagContext(null, Collections.emptyList(), rewrittenQuery, false);
        }
    
        // 跨知识库去重：按 content 前 100 字 hash 去重（保留首次出现，丢弃后续重复）
        Set<String> seenHash = new HashSet<>();
        List<KbSearchResultDTO> deduped = new ArrayList<>();
        for (KbSearchResultDTO r : allResults) {
            String prefix = r.getContent() != null
                    ? r.getContent().substring(0, Math.min(100, r.getContent().length()))
                    : "";
            if (seenHash.add(prefix)) {
                deduped.add(r);
            }
        }
    
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从知识库中检索到的参考资料，请在回答时优先参考这些内容，并在回答中标注引用来源（如 [1]、[2]）：\n\n");
        List<ChatReferenceVO> references = new ArrayList<>();
        for (int i = 0; i < deduped.size(); i++) {
            KbSearchResultDTO r = deduped.get(i);
            sb.append('[').append(i + 1).append("] ");
            if (r.getFileName() != null) {
                sb.append("来源：").append(r.getFileName()).append('\n');
            }
            sb.append(r.getContent()).append("\n\n");
    
            ChatReferenceVO ref = new ChatReferenceVO();
            ref.setKbId(r.getKbId());
            ref.setKbName(r.getKbId() != null && optionMap.get(r.getKbId()) != null
                    ? optionMap.get(r.getKbId()).getName() : null);
            ref.setDocId(r.getDocId());
            ref.setChunkId(r.getChunkId());
            ref.setFileName(r.getFileName());
            ref.setScore(r.getScore());
            ref.setContent(r.getContent() != null && r.getContent().length() > REFERENCE_CONTENT_MAX
                    ? r.getContent().substring(0, REFERENCE_CONTENT_MAX) + "…"
                    : r.getContent());
            references.add(ref);
        }
        return new RagContext(sb.toString().stripTrailing(), references, rewrittenQuery, false);
    }
    
    /**
     * 意图路由 + 查询改写（S69）：取最近 {@link #REWRITE_HISTORY_LIMIT} 条历史用户问题 + 当前问题，
     * 用当前对话目标（轮询起点 Key，不额外消耗轮询位）单次调用同时完成
     * 「是否需要检索」与「改写为独立检索语句」两个判断（JSON 输出）。
     * 只喂用户消息：assistant 长回复会诱导模型续答而非判断，且徒增 token。
     * rewriteEnabled=false（所有选中知识库改写开关均关）时仅采纳 needSearch，检索词强制原问题。
     * 任何异常 / JSON 解析失败 / 字段缺失均降级为「检索 + 原问题」（宁可多检不可漏检）。
     */
    private RouteDecision routeQuery(ChatTarget target, List<Message> history, String query, boolean rewriteEnabled) {
        RouteDecision fallback = new RouteDecision(true, query);
        try {
            List<Message> userMessages = history.stream()
                    .filter(m -> m instanceof UserMessage)
                    .toList();
            List<Message> recent = userMessages.size() > REWRITE_HISTORY_LIMIT
                    ? userMessages.subList(userMessages.size() - REWRITE_HISTORY_LIMIT, userMessages.size())
                    : userMessages;
            ChatClient client = pickClient(target, 0);
            ChatClient.ChatClientRequestSpec spec = client.prompt()
                    .system(ROUTE_SYSTEM_PROMPT)
                    .messages(recent)
                    .user(query);
            if (target.dynamic() && target.model() != null && !target.model().isBlank()) {
                spec = spec.options(clientRegistry.buildChatOptions(target.provider(), target.model()));
            }
            // S68/S69：意图路由 + 查询改写（rag 场景单独计量）
            ChatClient.ChatClientRequestSpec finalSpec = spec;
            String content = AiUsageContext.callWithScene(AiUsageContext.SCENE_RAG, () -> finalSpec.call().content());
            if (content == null || content.isBlank()) {
                return fallback;
            }
            // 容忍模型包代码围栏等杂质：截取首个 { 到末个 } 之间的 JSON 片段
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.warn("[PivotOS] RAG 意图路由输出非 JSON，降级检索: {}", content.strip());
                return fallback;
            }
            JSONObject json = JSON.parseObject(content.substring(start, end + 1));
            if (!json.getBooleanValue("needSearch", true)) {
                log.info("[PivotOS] RAG 意图路由出局（无需检索）：问题={}", query);
                return new RouteDecision(false, query);
            }
            String routed = json.getString("query");
            if (!rewriteEnabled || routed == null || routed.isBlank() || routed.strip().equals(query.strip())) {
                return new RouteDecision(true, query);
            }
            log.info("[PivotOS] RAG 查询改写生效：原问题={} 改写后={}", query, routed.strip());
            return new RouteDecision(true, routed.strip());
        } catch (Exception e) {
            log.warn("[PivotOS] RAG 意图路由调用失败，降级原问题检索: {}", e.getMessage());
            return fallback;
        }
    }
    
    /** 引用列表序列化为 JSON（空列表返回 null，不落无意义空数组） */
    private String referencesJson(List<ChatReferenceVO> references) {
        return references == null || references.isEmpty() ? null : JSON.toJSONString(references);
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
    private AiChatMessage saveMessage(AiConversation conversation, Long userId, String role,
                                      String content, String referencesJson) {
        LocalDateTime now = LocalDateTime.now();
        AiChatMessage message = new AiChatMessage();
        message.setConversationId(conversation.getId());
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setReferences(referencesJson);
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
        // S68：历史消息引用从持久化 JSON 反序列化回填（解析失败静默置空，不阻塞历史加载）
        if (message.getReferences() != null && !message.getReferences().isBlank()) {
            try {
                vo.setReferences(JSON.parseArray(message.getReferences(), ChatReferenceVO.class));
            } catch (Exception e) {
                log.warn("[PivotOS] 消息引用 JSON 解析失败，置空: messageId={}, reason={}",
                        message.getId(), e.getMessage());
            }
        }
        return vo;
    }
}
