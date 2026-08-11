package com.pivotos.ai.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * OpenAI 兼容供应商工厂（兜底）：未识别 code 一律走此实现——
 * 即 dashscope 等 OpenAI 兼容协议供应商，保持 S46 之前的现状行为不变。
 *
 * <p>Spring AI 2.0 官方 SDK 约定：base-url/api-key/model 全部收敛在 OpenAiChatOptions，
 * build() 时自动装配底层 OpenAIClient（base-url 须含 /v1）。
 */
@Component
public class OpenAiCompatibleChatModelFactory implements ChatModelFactory {

    private final RestClient restClient = RestClient.create();

    /** 兜底：任意 code 均可（注册表保证只在无专门工厂匹配时命中本实现） */
    @Override
    public boolean supports(String code) {
        return true;
    }

    @Override
    public boolean isFallback() {
        return true;
    }

    @Override
    public ChatModel buildChatModel(AiProvider provider, AiApiKey key) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(key.getApiKey());
        if (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()) {
            options.model(provider.getDefaultModel());
        }
        return OpenAiChatModel.builder()
                .options(options.build())
                .build();
    }

    @Override
    public ChatOptions.Builder<?> buildChatOptions(String model) {
        return OpenAiChatOptions.builder().model(model);
    }

    /** OpenAI 兼容 GET {base-url}/models（Bearer 头），返回 data[].id */
    @Override
    public List<String> listModels(AiProvider provider, AiApiKey key) {
        String body = restClient.get()
                .uri(provider.getBaseUrl() + "/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.getApiKey())
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
}
