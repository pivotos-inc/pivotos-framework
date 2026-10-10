package com.pivotos.ai.facade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.ai.api.dto.AiChatStatsDTO;
import com.pivotos.ai.api.dto.AiTrendPointDTO;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.ai.client.AiClientRegistry;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiChatMessage;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.mapper.AiApiKeyMapper;
import com.pivotos.ai.mapper.AiChatMessageMapper;
import com.pivotos.ai.mapper.AiConversationMapper;
import com.pivotos.ai.mapper.AiProviderMapper;
import com.pivotos.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IAiFacade 本地实现（单体形态）
 *
 * <p>其他 Plugin 只注入 IAiFacade 契约；无状态单轮生成，不落会话/消息表。
 */
@Component
@RequiredArgsConstructor
public class AiLocalFacade implements IAiFacade {

    private static final Logger log = LoggerFactory.getLogger(AiLocalFacade.class);

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 无启用供应商时 {@link #resolveModel(String)} 的返回值：表示「走静态兜底通道」 */
    private static final String STATIC_MODEL = "static";

    /** ChatClient 可能不存在（api-key 未配置），懒获取 + 5020 兜底 */
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final AiClientRegistry clientRegistry;
    private final AiConversationMapper conversationMapper;
    private final AiChatMessageMapper messageMapper;
    private final AiProviderMapper providerMapper;
    private final AiApiKeyMapper apiKeyMapper;

    @Override
    public String chat(String prompt) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        try {
            return chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.error("[PivotOS] AI 单轮生成失败", e);
            throw new ServiceException(AiErrorCode.CHAT_FAILED);
        }
    }

    @Override
    public String chatWithSystem(String systemPrompt, String userPrompt) {
        // 动态解析链：首个启用供应商 + 该供应商首个启用 Key（与 plugin-ai-coding 意图解析同口径）
        List<AiProvider> providers = providerMapper.selectList(Wrappers.<AiProvider>lambdaQuery()
                .eq(AiProvider::getStatus, 0)
                .orderByAsc(AiProvider::getId));
        if (providers.isEmpty()) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        AiProvider provider = providers.get(0);
        List<AiApiKey> keys = apiKeyMapper.selectList(Wrappers.<AiApiKey>lambdaQuery()
                .eq(AiApiKey::getProviderId, provider.getId())
                .eq(AiApiKey::getStatus, 0)
                .orderByAsc(AiApiKey::getId));
        if (keys.isEmpty()) {
            throw new ServiceException(AiErrorCode.NO_AVAILABLE_KEY);
        }
        try {
            // S96 K7：DashScope 兼容端对 [system, user] 形态的特定结构化 prompt 实测稳定返回空数组（[]），
            // 同内容拼入单条 user 消息则正常产出；内部生成链路改用单 user 形态规避
            return clientRegistry.getInternalChatClient(provider, keys.get(0))
                    .prompt()
                    .user(systemPrompt + "\n\n" + userPrompt)
                    .options(clientRegistry.buildChatOptions(provider, provider.getDefaultModel()))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[PivotOS] AI 单轮生成失败 providerId={}", provider.getId(), e);
            throw new ServiceException(AiErrorCode.CHAT_FAILED);
        }
    }

    @Override
    public AiChatStatsDTO chatStats(int days) {
        AiChatStatsDTO dto = new AiChatStatsDTO();
        dto.setConversationCount(conversationMapper.selectCount(null));
        dto.setMessageCount(messageMapper.selectCount(null));
        dto.setProviderCount(providerMapper.selectCount(null));
        // Key 健康口径：status=0 启用；启用且 failCount>0 视为不健康
        List<AiApiKey> activeKeys = apiKeyMapper.selectList(Wrappers.<AiApiKey>lambdaQuery()
                .eq(AiApiKey::getStatus, 0));
        dto.setActiveKeyCount((long) activeKeys.size());
        dto.setUnhealthyKeyCount(activeKeys.stream()
                .filter(k -> k.getFailCount() != null && k.getFailCount() > 0).count());
        dto.setMessageTrend(messageTrend(Math.max(days, 0)));
        return dto;
    }

    /**
     * 内部生成链路：动态通道优先，静态兜底（V3-S1 契约治理）
     *
     * <p>这段逻辑原先在 plugin-ai-coding 的 {@code DynamicLocateLlmClient} 与 plugin-ai 的
     * {@code PlanDraftLlmClient} 里<b>各写了一遍</b>——同一份「选供应商 → 选 Key → 组 spec →
     * 取不到就静态兜底」，现在是唯一实现，两个消费方都走本契约。
     */
    @Override
    public String internalChat(String systemPrompt, String userPrompt, String model) {
        AiProvider provider = firstEnabledProvider();
        AiApiKey key = provider == null ? null : firstEnabledKey(provider.getId());
        boolean override = model != null && !model.isBlank();
        ChatClient.ChatClientRequestSpec spec = null;
        if (provider != null && key != null) {
            log.debug("[PivotOS] 内部链路走动态通道：provider={}", provider.getCode());
            spec = clientRegistry.getInternalChatClient(provider, key)
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt);
            if (override) {
                spec = spec.options(clientRegistry.buildChatOptions(provider, model));
            }
        } else {
            ChatClient client = chatClientProvider.getIfAvailable();
            if (client != null) {
                log.debug("[PivotOS] 内部链路走静态兜底通道");
                spec = client.prompt().system(systemPrompt).user(userPrompt);
                if (override) {
                    spec = spec.options(OpenAiChatOptions.builder().model(model));
                }
            }
        }
        if (spec == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        ChatClient.ChatClientRequestSpec finalSpec = spec;
        try {
            return finalSpec.call().content();
        } catch (Exception e) {
            log.error("[PivotOS] 内部链路 LLM 调用失败", e);
            throw new ServiceException(AiErrorCode.CHAT_FAILED);
        }
    }

    @Override
    public String dynamicChat(String systemPrompt, String userPrompt) {
        AiProvider provider = firstEnabledProvider();
        if (provider == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        AiApiKey key = firstEnabledKey(provider.getId());
        if (key == null) {
            throw new ServiceException(AiErrorCode.NO_AVAILABLE_KEY);
        }
        try {
            return clientRegistry.getChatClient(provider, key)
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[PivotOS] 动态通道 LLM 调用失败 providerId={}", provider.getId(), e);
            throw new ServiceException(AiErrorCode.CHAT_FAILED);
        }
    }

    @Override
    public String resolveModel(String modelOverride) {
        if (modelOverride != null && !modelOverride.isBlank()) {
            return modelOverride.trim();
        }
        AiProvider provider = firstEnabledProvider();
        return provider == null ? STATIC_MODEL : provider.getDefaultModel();
    }

    /** 首个启用供应商（status=0 启用，口径同 AiChatServiceImpl / 原 ai-coding 实现） */
    private AiProvider firstEnabledProvider() {
        List<AiProvider> providers = providerMapper.selectList(Wrappers.<AiProvider>lambdaQuery()
                .eq(AiProvider::getStatus, 0));
        return providers.isEmpty() ? null : providers.get(0);
    }

    /** 供应商下首个启用 Key */
    private AiApiKey firstEnabledKey(Long providerId) {
        List<AiApiKey> keys = apiKeyMapper.selectList(Wrappers.<AiApiKey>lambdaQuery()
                .eq(AiApiKey::getProviderId, providerId)
                .eq(AiApiKey::getStatus, 0));
        return keys.isEmpty() ? null : keys.get(0);
    }

    /** 近 N 日每日消息趋势（缺日补 0） */
    private List<AiTrendPointDTO> messageTrend(int days) {
        if (days <= 0) {
            return List.of();
        }
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(days - 1L).atStartOfDay();
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i).format(DAY_FMT), 0L);
        }
        List<AiChatMessage> messages = messageMapper.selectList(Wrappers.<AiChatMessage>lambdaQuery()
                .select(AiChatMessage::getCreateTime)
                .ge(AiChatMessage::getCreateTime, start));
        for (AiChatMessage message : messages) {
            String day = message.getCreateTime().toLocalDate().format(DAY_FMT);
            byDay.computeIfPresent(day, (k, v) -> v + 1);
        }
        return byDay.entrySet().stream()
                .map(e -> new AiTrendPointDTO(e.getKey(), e.getValue()))
                .toList();
    }
}
