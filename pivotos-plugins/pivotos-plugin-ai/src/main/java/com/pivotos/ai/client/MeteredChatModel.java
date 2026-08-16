package com.pivotos.ai.client;

import com.pivotos.ai.client.AiUsageRecorder.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 计量 ChatModel 装饰器（S92 Token 计量的唯一收敛点）
 *
 * <p>{@link AiClientRegistry#buildClient} 构建动态 ChatClient 时以本类包装工厂产出的 ChatModel：
 * 对话/Coding/图表/RAG 路由四条链路凡走动态 Key 体系者，call/stream 均在此记录 token 用量。
 *
 * <p>上下文抓取时机：call 全程在请求线程；stream 的组装（本方法体）也在请求线程，
 * 故 {@link Snapshot#capture()} 在此执行，doFinally 回调线程用快照落库（与流式 saveMessage 同思路）。
 * 供应商流式不返回 usage 时记 0（口径「未计量」），失败调用记 0 + failed=1。
 */
public class MeteredChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(MeteredChatModel.class);

    private final ChatModel delegate;
    private final Long providerId;
    private final String providerCode;
    private final Long keyId;
    private final AiUsageRecorder recorder;

    public MeteredChatModel(ChatModel delegate, Long providerId, String providerCode,
                            Long keyId, AiUsageRecorder recorder) {
        this.delegate = delegate;
        this.providerId = providerId;
        this.providerCode = providerCode;
        this.keyId = keyId;
        this.recorder = recorder;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Snapshot snapshot = Snapshot.capture();
        try {
            ChatResponse response = delegate.call(prompt);
            Usage usage = usageOf(response);
            recorder.record(snapshot, providerId, providerCode, keyId,
                    modelOf(response, prompt), "chat",
                    promptTokens(usage), completionTokens(usage), false);
            return response;
        } catch (RuntimeException e) {
            recorder.record(snapshot, providerId, providerCode, keyId,
                    modelOf(null, prompt), "chat", 0, 0, true);
            throw e;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 组装发生在请求线程：此处抓取上下文快照
        Snapshot snapshot = Snapshot.capture();
        String fallbackModel = modelOf(null, prompt);
        // 流内局部承载：本 client 被缓存复用，多流并发不可共享模型名状态
        String[] modelHolder = new String[1];
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        return delegate.stream(prompt)
                .doOnNext(response -> {
                    Usage usage = usageOf(response);
                    if (usage != null) {
                        lastUsage.set(usage);
                    }
                    // 流中块可能携带更精确的模型名（首块 metadata）
                    if (response != null && response.getMetadata() != null
                            && response.getMetadata().getModel() != null) {
                        // model 为 effectively-final 约束下以局部数组承载
                        modelHolder[0] = response.getMetadata().getModel();
                    }
                })
                .doFinally(signal -> {
                    try {
                        Usage usage = lastUsage.get();
                        boolean ok = signal == SignalType.ON_COMPLETE;
                        recorder.record(snapshot, providerId, providerCode, keyId,
                                modelHolder[0] != null ? modelHolder[0] : fallbackModel, "chat",
                                promptTokens(usage), completionTokens(usage), !ok);
                    } catch (Exception e) {
                        log.warn("[PivotOS] 流式用量记录异常（不影响业务）：{}", e.getMessage());
                    }
                });
    }

    @Override
    public ChatOptions getOptions() {
        // Spring AI 2.0 新契约：ChatClient 以 getOptions().mutate() 装配 Prompt options，
        // 必须透传被包装者的真实 options 类型（如 OpenAiChatOptions 携 baseUrl/apiKey），
        // 否则降级为 DefaultChatOptions 会在供应商 SDK 内部强转时抛 CCE
        return delegate.getOptions();
    }

    // ---------- 内部工具 ----------

    private Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    /** 模型名：响应 metadata 优先，其次 prompt options */
    private String modelOf(ChatResponse response, Prompt prompt) {
        if (response != null && response.getMetadata() != null
                && response.getMetadata().getModel() != null) {
            return response.getMetadata().getModel();
        }
        if (prompt != null && prompt.getOptions() != null && prompt.getOptions().getModel() != null) {
            return prompt.getOptions().getModel();
        }
        return "";
    }

    private int promptTokens(Usage usage) {
        return usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    }

    private int completionTokens(Usage usage) {
        return usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    }
}
