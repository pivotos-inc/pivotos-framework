package com.pivotos.ai.client;

import com.pivotos.ai.client.AiUsageRecorder.Snapshot;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.service.AiProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * 动态 EmbeddingModel 委托：按数据库配置的 embedding/all 用途 Key 装配
 * {@link OpenAiEmbeddingModel}，无动态配置时回退 {@code spring.ai.openai.*} 静态配置。
 *
 * <p>设计对称于 {@link AiClientRegistry}：管理链路变更 Key/供应商时由
 * {@link AiProviderService#evictEmbeddingModel()} 失效委托，下次调用按最新配置重建。
 * VectorStore（Simple/Milvus）注入本 Bean（@Primary），不感知底层 Key 切换。
 *
 * <p>线程安全：delegate 用 volatile + 双重检查锁；evict 后重建可能短暂并发，
 * 各自构建一个 OpenAiEmbeddingModel 无副作用（SDK 内部 HTTP 客户端可复用）。
 */
public class DelegatingEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DelegatingEmbeddingModel.class);

    private final ObjectProvider<AiProviderService> providerServiceProvider;
    private final Environment environment;
    /** Token 用量记录器（S92：向量化调用计量，可为 null 表示不计量） */
    private final AiUsageRecorder usageRecorder;

    /** 当前委托（volatile 保证 evict 后立即可见） */
    private volatile EmbeddingModel delegate;

    /** 当前委托对应的供应商/Key/模型身份（静态兜底时 providerId/keyId 为 null） */
    private volatile Long currentProviderId;
    private volatile String currentProviderCode;
    private volatile Long currentKeyId;
    private volatile String currentModel;

    public DelegatingEmbeddingModel(ObjectProvider<AiProviderService> providerServiceProvider,
                                   Environment environment, AiUsageRecorder usageRecorder) {
        this.providerServiceProvider = providerServiceProvider;
        this.environment = environment;
        this.usageRecorder = usageRecorder;
    }

    /** 失效委托：下次调用按最新配置重建 */
    public void evict() {
        this.delegate = null;
        log.info("[PivotOS] 动态 EmbeddingModel 委托已失效，下次调用将重建");
    }

    // ---------- EmbeddingModel 接口实现（全部委托） ----------

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingResponse response;
        try {
            response = resolve().call(request);
        } catch (RuntimeException e) {
            recordUsage(null, true);
            throw e;
        }
        recordUsage(response, false);
        return response;
    }

    /** S92：向量化用量落库（usage 缺失记 0，计量异常不影响业务） */
    private void recordUsage(EmbeddingResponse response, boolean failed) {
        if (usageRecorder == null) {
            return;
        }
        try {
            Snapshot snapshot = Snapshot.capture();
            org.springframework.ai.chat.metadata.Usage usage =
                    response != null && response.getMetadata() != null
                            ? response.getMetadata().getUsage() : null;
            int prompt = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            int completion = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            usageRecorder.record(snapshot, currentProviderId,
                    currentProviderCode == null ? "static" : currentProviderCode,
                    currentKeyId, currentModel == null ? "" : currentModel,
                    "embedding", prompt, completion, failed);
        } catch (Exception e) {
            log.warn("[PivotOS] 向量化用量记录异常（不影响业务）：{}", e.getMessage());
        }
    }

    @Override
    public float[] embed(Document document) {
        return resolve().embed(document);
    }

    @Override
    public float[] embed(String text) {
        return resolve().embed(text);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return resolve().embed(texts);
    }

    @Override
    public EmbeddingResponse embedForResponse(List<String> texts) {
        return resolve().embedForResponse(texts);
    }

    @Override
    public int dimensions() {
        return resolve().dimensions();
    }

    // ---------- 委托解析 ----------

    /**
     * 解析当前委托：双重检查锁避免每次调用都进同步块。
     * ① 动态：数据库 embedding/all 用途 Key → 构建 OpenAiEmbeddingModel；
     * ② 静态兜底：spring.ai.openai.* 配置 → 构建 OpenAiEmbeddingModel；
     * ③ 均不可用 → 抛异常（KB 向量化功能不可用，不影响应用启动）。
     */
    private EmbeddingModel resolve() {
        EmbeddingModel current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (delegate != null) {
                return delegate;
            }
            delegate = buildDelegate();
            return delegate;
        }
    }

    /** 构建委托：优先数据库动态 Key，无则静态配置兜底 */
    private EmbeddingModel buildDelegate() {
        // ① 动态：数据库 embedding/all 用途 Key
        AiProviderService service = providerServiceProvider.getIfAvailable();
        if (service != null) {
            AiProvider provider = service.findEmbeddingProvider();
            if (provider != null) {
                List<AiApiKey> keys = service.listActiveEmbeddingKeys(provider.getId());
                if (!keys.isEmpty()) {
                    AiApiKey key = keys.get(0);
                    String modelName = resolveEmbeddingModelName(provider);
                    log.info("[PivotOS] 动态 EmbeddingModel 构建：providerId={} keyId={} model={}",
                            provider.getId(), key.getId(), modelName);
                    setCurrentIdentity(provider.getId(), provider.getCode(), key.getId(), modelName);
                    return buildOpenAiEmbeddingModel(provider.getBaseUrl(), key.getApiKey(), modelName);
                }
            }
        }

        // ② 静态兜底：spring.ai.openai.*
        String apiKey = environment.getProperty("spring.ai.openai.api-key", "");
        String baseUrl = environment.getProperty("spring.ai.openai.base-url", "");
        String modelName = environment.getProperty("spring.ai.openai.embedding.options.model", "");
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "EmbeddingModel 未就绪：数据库无 embedding/all 用途 Key，"
                            + "且 spring.ai.openai.api-key/base-url 未配置");
        }
        log.info("[PivotOS] EmbeddingModel 回退静态配置：baseUrl={} model={}", baseUrl, modelName);
        setCurrentIdentity(null, null, null, modelName);
        return buildOpenAiEmbeddingModel(baseUrl, apiKey, modelName);
    }

    /** 记录当前委托身份（供计量使用，与 delegate 同在锁内更新） */
    private void setCurrentIdentity(Long providerId, String providerCode, Long keyId, String model) {
        this.currentProviderId = providerId;
        this.currentProviderCode = providerCode;
        this.currentKeyId = keyId;
        this.currentModel = model;
    }

    /** 解析向量化模型名：供应商 embeddingModel 优先，空则回退 spring.ai.openai.embedding.options.model */
    private String resolveEmbeddingModelName(AiProvider provider) {
        if (provider.getEmbeddingModel() != null && !provider.getEmbeddingModel().isBlank()) {
            return provider.getEmbeddingModel();
        }
        return environment.getProperty("spring.ai.openai.embedding.options.model", "");
    }

    /** 构建 OpenAiEmbeddingModel（baseUrl 须含 /v1） */
    private EmbeddingModel buildOpenAiEmbeddingModel(String baseUrl, String apiKey, String model) {
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey);
        if (model != null && !model.isBlank()) {
            options.model(model);
        }
        return OpenAiEmbeddingModel.builder()
                .options(options.build())
                .build();
    }
}
