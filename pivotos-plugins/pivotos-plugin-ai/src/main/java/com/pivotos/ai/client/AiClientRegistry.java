package com.pivotos.ai.client;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态 AI 客户端注册表：按（供应商 × Key）缓存 ChatClient，供应商/Key 变更时由
 * AiProviderService 调 evict 失效。轮询计数器实现同供应商多 Key 负载分摊；
 * 模型列表 5 分钟 TTL 缓存（避免下拉每开一次就打供应商）。
 *
 * <p>S46 起 client 装配与模型列表按 provider.code 委托 {@link ChatModelFactory} 分派：
 * anthropic/gemini 走各自官方 SDK，未识别 code 回落 OpenAI 兼容兜底（现状行为不变）；
 * 对话/落库/SSE/健康度链路不感知供应商差异（OpenAI 规范红线，差异在适配层消化）。
 */
@Component
@RequiredArgsConstructor
public class AiClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiClientRegistry.class);

    /** 模型列表缓存 TTL（毫秒） */
    private static final long MODEL_CACHE_TTL = 5 * 60 * 1000L;

    private final AiProperties aiProperties;

    /** 全部供应商工厂（含 OpenAI 兼容兜底），按 code 分派 */
    private final List<ChatModelFactory> factories;

    /** ChatClient 缓存：providerId:keyId → client */
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    /** 轮询计数器：providerId → 单调递增序号 */
    private final Map<Long, AtomicLong> roundRobin = new ConcurrentHashMap<>();

    /** 模型列表缓存：providerId → (模型列表, 过期时间戳) */
    private final Map<Long, ModelCacheEntry> modelCache = new ConcurrentHashMap<>();

    /** 取（供应商 × Key）对应的 ChatClient（缓存命中则复用底层连接） */
    public ChatClient getChatClient(AiProvider provider, AiApiKey key) {
        String cacheKey = provider.getId() + ":" + key.getId();
        return clientCache.computeIfAbsent(cacheKey, k -> buildClient(provider, key));
    }

    /** 轮询起点：同供应商多 Key 依次分摊 */
    public int nextKeyIndex(Long providerId, int size) {
        long seq = roundRobin.computeIfAbsent(providerId, id -> new AtomicLong()).getAndIncrement();
        return (int) (seq % size);
    }

    /** 供应商配置变更/删除：失效其全部 client 与模型缓存 */
    public void evictProvider(Long providerId) {
        String prefix = providerId + ":";
        clientCache.keySet().removeIf(k -> k.startsWith(prefix));
        modelCache.remove(providerId);
    }

    /** 单个 Key 变更/删除：只失效对应 client */
    public void evictKey(Long providerId, Long keyId) {
        clientCache.remove(providerId + ":" + keyId);
    }

    /**
     * 查询供应商可用模型：按 code 委托对应工厂拉取（各家鉴权头/响应结构差异在工厂内消化）。
     * 失败统一 5023——常见原因：base-url 版本段不对、Key 无效、网络不可达。
     */
    public List<String> listModels(AiProvider provider, AiApiKey key) {
        ModelCacheEntry cached = modelCache.get(provider.getId());
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.models();
        }
        try {
            List<String> models = resolveFactory(provider.getCode()).listModels(provider, key);
            modelCache.put(provider.getId(), new ModelCacheEntry(models, System.currentTimeMillis() + MODEL_CACHE_TTL));
            return models;
        } catch (Exception e) {
            // 不打 Key，避免敏感信息进日志
            log.warn("[PivotOS] 模型列表查询失败 providerId={} code={} baseUrl={}：{}",
                    provider.getId(), provider.getCode(), provider.getBaseUrl(), e.getMessage());
            throw new ServiceException(AiErrorCode.MODEL_LIST_FAILED);
        }
    }

    /**
     * 构建 per-request options（仅模型覆盖）：按 code 委托工厂产出对应 SDK 的 options
     * Builder 上转型，供 ChatClientRequestSpec.options() 与 client 默认 options 合并。
     */
    public ChatOptions.Builder<?> buildChatOptions(AiProvider provider, String model) {
        return resolveFactory(provider.getCode()).buildChatOptions(model);
    }

    /** 按 code 分派工厂：专门工厂优先，未识别 code 回落 OpenAI 兼容兜底（向后兼容） */
    private ChatModelFactory resolveFactory(String code) {
        ChatModelFactory fallback = null;
        for (ChatModelFactory factory : factories) {
            if (factory.isFallback()) {
                fallback = factory;
                continue;
            }
            if (code != null && factory.supports(code)) {
                return factory;
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("OpenAI 兼容兜底 ChatModelFactory 未注册");
        }
        return fallback;
    }

    /** 构建动态 ChatClient（模型为供应商默认值，实际对话时按请求 options 覆盖） */
    private ChatClient buildClient(AiProvider provider, AiApiKey key) {
        ChatModelFactory factory = resolveFactory(provider.getCode());
        log.info("[PivotOS] 动态 ChatClient 构建：providerId={} keyId={} code={} factory={}",
                provider.getId(), key.getId(), provider.getCode(), factory.getClass().getSimpleName());
        return ChatClient.builder(factory.buildChatModel(provider, key))
                .defaultSystem(aiProperties.getSystemPrompt())
                .build();
    }

    private record ModelCacheEntry(List<String> models, long expireAt) {
    }
}
