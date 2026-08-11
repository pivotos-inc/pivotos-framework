package com.pivotos.ai.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anthropic（Claude）供应商工厂，约定 code={@value #CODE}。
 *
 * <p>适配层消化的差异：SDK 用官方 anthropic-java（api-key/base-url 收敛进
 * AnthropicChatOptions，base-url 约定为不含 /v1 的源站，SDK 自行追加版本段）；
 * 模型列表走 {@code GET /v1/models}，鉴权头为 {@code x-api-key} +
 * {@code anthropic-version}（与 OpenAI 的 Bearer 头不同），响应 data[].id 同构。
 *
 * <p>Anthropic 协议要求 max_tokens 必填：默认 {@value #DEFAULT_MAX_TOKENS} 收敛在
 * client 级默认 options，per-request 只覆盖模型名（合并时沿用该默认值）。
 */
@Component
public class AnthropicChatModelFactory implements ChatModelFactory {

    /** 保留字 code */
    public static final String CODE = "anthropic";

    /** anthropic-version 头（API 契约版本，非 SDK 版本） */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** base-url 缺省值（官方源站，不含 /v1） */
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    /** max_tokens 缺省值（Anthropic 协议必填） */
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private final RestClient restClient = RestClient.create();

    @Override
    public boolean supports(String code) {
        return CODE.equalsIgnoreCase(code);
    }

    @Override
    public ChatModel buildChatModel(AiProvider provider, AiApiKey key) {
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder();
        // 继承自基类的 setter 返回基类 Builder 类型，无法与特有 setter 链式混排，逐条调用
        options.apiKey(key.getApiKey());
        options.baseUrl(normalizeBaseUrl(provider.getBaseUrl()));
        options.maxTokens(DEFAULT_MAX_TOKENS);
        if (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()) {
            options.model(provider.getDefaultModel());
        }
        return AnthropicChatModel.builder()
                .options(options.build())
                .build();
    }

    @Override
    public ChatOptions.Builder<?> buildChatOptions(String model) {
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder();
        options.model(model);
        return options;
    }

    /** Anthropic 原生 GET {base}/v1/models：x-api-key + anthropic-version 头，返回 data[].id */
    @Override
    public List<String> listModels(AiProvider provider, AiApiKey key) {
        String body = restClient.get()
                .uri(normalizeBaseUrl(provider.getBaseUrl()) + "/v1/models")
                .header("x-api-key", key.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .retrieve()
                .body(String.class);
        JSONArray data = JSONObject.parseObject(body).getJSONArray("data");
        return data == null ? List.of()
                : data.stream()
                        .map(item -> ((JSONObject) item).getString("id"))
                        .filter(id -> id != null && !id.isBlank())
                        .sorted()
                        .toList();
    }

    /** base-url 归一：去尾斜杠、去尾部 /v1（SDK 自行追加版本段），缺省回落官方源站 */
    private String normalizeBaseUrl(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base;
    }
}
