package com.pivotos.ai.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Google Gemini 供应商工厂，约定 code={@value #CODE}（S46 拍板：原生 SDK 路径，
 * 与 Anthropic 同构走工厂模式，不走 OpenAI 兼容端点）。
 *
 * <p>适配层消化的差异：SDK 用官方 google-genai {@link Client}（apiKey + httpOptions.baseUrl，
 * base-url 约定为不含 /v1beta 的源站，SDK 自行追加版本段）；模型列表走原生
 * {@code GET /v1beta/models?key=}，响应 models[].name 带 {@code models/} 前缀，此处剥掉，
 * 统一对外返回裸模型 ID（OpenAI 规范红线）。
 */
@Component
public class GoogleGenAiChatModelFactory implements ChatModelFactory {

    /** 保留字 code */
    public static final String CODE = "gemini";

    /** base-url 缺省值（官方源站，不含 /v1beta） */
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    /** 模型列表响应 name 前缀（"models/gemini-xxx" → "gemini-xxx"） */
    private static final String MODEL_NAME_PREFIX = "models/";

    private final RestClient restClient = RestClient.create();

    @Override
    public boolean supports(String code) {
        return CODE.equalsIgnoreCase(code);
    }

    @Override
    public ChatModel buildChatModel(AiProvider provider, AiApiKey key) {
        Client.Builder clientBuilder = Client.builder().apiKey(key.getApiKey());
        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        clientBuilder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
        Client client = clientBuilder.build();
        GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder();
        if (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()) {
            options.model(provider.getDefaultModel());
        }
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(options.build())
                .build();
    }

    @Override
    public ChatOptions.Builder<?> buildChatOptions(String model) {
        GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder();
        options.model(model);
        return options;
    }

    /** Gemini 原生 GET {base}/v1beta/models?key=：models[].name 剥 models/ 前缀 */
    @Override
    public List<String> listModels(AiProvider provider, AiApiKey key) {
        String body = restClient.get()
                .uri(normalizeBaseUrl(provider.getBaseUrl()) + "/v1beta/models?key={key}",
                        key.getApiKey())
                .retrieve()
                .body(String.class);
        JSONArray models = JSONObject.parseObject(body).getJSONArray("models");
        return models == null ? List.of()
                : models.stream()
                        .map(item -> ((JSONObject) item).getString("name"))
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> name.startsWith(MODEL_NAME_PREFIX)
                                ? name.substring(MODEL_NAME_PREFIX.length()) : name)
                        .sorted()
                        .toList();
    }

    /** base-url 归一：去尾斜杠、去尾部 /v1beta（SDK 自行追加版本段），缺省回落官方源站 */
    private String normalizeBaseUrl(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1beta")) {
            base = base.substring(0, base.length() - "/v1beta".length());
        }
        return base;
    }
}
