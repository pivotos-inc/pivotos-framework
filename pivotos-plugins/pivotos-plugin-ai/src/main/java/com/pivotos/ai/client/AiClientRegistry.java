package com.pivotos.ai.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态 AI 客户端注册表：按（供应商 × Key）缓存 ChatClient，供应商/Key 变更时由
 * AiProviderService 调 evict 失效。轮询计数器实现同供应商多 Key 负载分摊；
 * 模型列表走 OpenAI 兼容 GET {base-url}/models，5 分钟 TTL 缓存（避免下拉每开一次就打供应商）。
 *
 * <p>Spring AI 2.0 官方 SDK 约定：base-url/api-key/model 全部收敛在 OpenAiChatOptions，
 * build() 时自动装配底层 OpenAIClient（base-url 须含 /v1）。
 */
@Component
@RequiredArgsConstructor
public class AiClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiClientRegistry.class);

    /** 模型列表缓存 TTL（毫秒） */
    private static final long MODEL_CACHE_TTL = 5 * 60 * 1000L;

    private final AiProperties aiProperties;

    /** ChatClient 缓存：providerId:keyId → client */
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    /** 轮询计数器：providerId → 单调递增序号 */
    private final Map<Long, AtomicLong> roundRobin = new ConcurrentHashMap<>();

    /** 模型列表缓存：providerId → (模型列表, 过期时间戳) */
    private final Map<Long, ModelCacheEntry> modelCache = new ConcurrentHashMap<>();

    private final RestClient restClient = RestClient.create();

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
     * 查询供应商可用模型（OpenAI 兼容 GET /models，返回 data[].id）。
     * 失败统一 5023——常见原因：base-url 缺 /v1、Key 无效、网络不可达。
     */
    public List<String> listModels(AiProvider provider, AiApiKey key) {
        ModelCacheEntry cached = modelCache.get(provider.getId());
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.models();
        }
        try {
            String body = restClient.get()
                    .uri(provider.getBaseUrl() + "/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.getApiKey())
                    .retrieve()
                    .body(String.class);
            JSONArray data = JSONObject.parseObject(body).getJSONArray("data");
            List<String> models = data == null ? List.of()
                    : data.stream()
                            .map(item -> ((JSONObject) item).getString("id"))
                            .filter(id -> id != null && !id.isBlank())
                            .sorted()
                            .toList();
            modelCache.put(provider.getId(), new ModelCacheEntry(models, System.currentTimeMillis() + MODEL_CACHE_TTL));
            return models;
        } catch (Exception e) {
            // 不打 Key，避免敏感信息进日志
            log.warn("[PivotOS] 模型列表查询失败 providerId={} baseUrl={}：{}",
                    provider.getId(), provider.getBaseUrl(), e.getMessage());
            throw new ServiceException(AiErrorCode.MODEL_LIST_FAILED);
        }
    }

    /** 构建动态 ChatClient（模型为供应商默认值，实际对话时按请求 options 覆盖） */
    private ChatClient buildClient(AiProvider provider, AiApiKey key) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(key.getApiKey());
        if (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()) {
            options.model(provider.getDefaultModel());
        }
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options.build())
                .build();
        log.info("[PivotOS] 动态 ChatClient 构建：providerId={} keyId={}", provider.getId(), key.getId());
        return ChatClient.builder(chatModel)
                .defaultSystem(aiProperties.getSystemPrompt())
                .build();
    }

    private record ModelCacheEntry(List<String> models, long expireAt) {
    }
}
