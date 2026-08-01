package com.pivotos.ai.coding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.ai.client.AiClientRegistry;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.mapper.AiApiKeyMapper;
import com.pivotos.ai.mapper.AiProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * LLM intent parsing: natural language -&gt; table schema JSON.
 * <p>
 * Gets first enabled provider + first active key, builds a ChatClient via
 * AiClientRegistry, sends a structured prompt to the LLM and parses the
 * JSON response.
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Service
public class IntentParseService {

    private static final Logger log = LoggerFactory.getLogger(IntentParseService.class);

    private final ObjectMapper objectMapper;
    private final AiClientRegistry registry;
    private final AiProviderMapper providerMapper;
    private final AiApiKeyMapper keyMapper;

    private static final String SYSTEM_PROMPT = """
            You are a database designer. Given a business description, output a table schema in JSON.
            Rules:
            1. moduleName: business domain (system/business/cms/etc.)
            2. functionName: Chinese feature name (max 10 chars)
            3. tableName: snake_case with domain prefix (e.g. biz_product)
            4. businessName: camelCase of core entity (e.g. product)
            5. tableComment: brief description
            6. columns: array of {columnName, columnType(MySQL), columnComment,
               javaType(String/Integer/Long/BigDecimal/LocalDateTime/Boolean),
               javaField(camelCase), isPk(0|1), isRequired(0|1),
               isQuery(0|1), queryType(EQ|LIKE), htmlType(input|textarea|datetime|switch)}
            7. Do NOT include id/createBy/createTime in columns (auto-generated)
            Output ONLY JSON, no markdown:
            {"moduleName":"...","functionName":"...","tableName":"...","businessName":"...","tableComment":"...","columns":[...]}
            """;

    public IntentParseService(ObjectMapper objectMapper, AiClientRegistry registry,
                              AiProviderMapper providerMapper, AiApiKeyMapper keyMapper) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.providerMapper = providerMapper;
        this.keyMapper = keyMapper;
    }

    public Map<String, Object> parse(String description) {
        log.info("[AI Coding] Intent parse: description={}", description);

        // Get first enabled provider
        List<AiProvider> providers = providerMapper.selectList(
                new LambdaQueryWrapper<AiProvider>().eq(AiProvider::getStatus, 0));
        if (providers.isEmpty()) {
            throw new RuntimeException("No enabled AI provider found. Please configure one in AI management.");
        }
        AiProvider provider = providers.get(0);

        // Get first active key for this provider
        List<AiApiKey> keys = keyMapper.selectList(
                new LambdaQueryWrapper<AiApiKey>()
                        .eq(AiApiKey::getProviderId, provider.getId())
                        .eq(AiApiKey::getStatus, 0));
        if (keys.isEmpty()) {
            throw new RuntimeException("No active API key for provider: " + provider.getName());
        }
        AiApiKey key = keys.get(0);

        // Intent parse via LLM
        String response = registry.getChatClient(provider, key)
                .prompt()
                .system(SYSTEM_PROMPT)
                .user("Business description: " + description + "\n\nOutput JSON schema:")
                .call()
                .content();

        log.info("[AI Coding] LLM response length={}", response != null ? response.length() : 0);
        if (response == null || response.isBlank()) {
            throw new RuntimeException("AI returned empty response");
        }

        String json = response.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            json = (start >= 0 && end > start) ? json.substring(start + 1, end).trim() : json;
        }

        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("[AI Coding] JSON parse failed: {}", response, e);
            throw new RuntimeException("AI response is not valid JSON", e);
        }
    }
}
