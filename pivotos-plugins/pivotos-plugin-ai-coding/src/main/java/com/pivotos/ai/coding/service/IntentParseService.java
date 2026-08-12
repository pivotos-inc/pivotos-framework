package com.pivotos.ai.coding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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
               isQuery(0|1), queryType(EQ|LIKE), htmlType(input|textarea|datetime|switch),
               isList(0|1, show in table), isInsert(0|1, show in create form), isEdit(0|1, show in edit form)}
            7. Do NOT include id/createBy/createTime in columns (auto-generated)
            8. isList/isInsert/isEdit default to 1; set 0 for fields that should be hidden
               (e.g. long text hidden from list via isList=0)
            Output ONLY JSON, no markdown:
            {"moduleName":"...","functionName":"...","tableName":"...","businessName":"...","tableComment":"...","columns":[...]}
            """;

    /** Plugin 骨架意图（S42）：LLM 只推断命名类参数，错误码段由 ErrorCodeSegmentAllocator 确定性分配 */
    private static final String PLUGIN_SYSTEM_PROMPT = """
            You are a Java plugin architect for the PivotOS platform. Given a business domain description,
            output a plugin skeleton plan in JSON.
            Rules:
            1. pluginName: lowercase english identifier, ^[a-z][a-z0-9]{1,15}$, single word or
               concatenated words (e.g. asset, contract, assetloan); must NOT be one of the
               reserved names: system, message, file, ai, generator, server, common, starter
            2. displayName: Chinese plugin display name (max 10 chars, e.g. 资产管理)
            3. tablePrefix: database table prefix, usually pluginName + "_" (e.g. asset_)
            4. moduleDesc: one-sentence Chinese module description for pom <description>
            Output ONLY JSON, no markdown:
            {"pluginName":"...","displayName":"...","tablePrefix":"...","moduleDesc":"..."}
            """;

    /** 主子表意图（S52 / 2.4-F5）：LLM 输出主子双表+关系；关系闭合由 SubIntentValidator 确定性校验 */
    private static final String SUB_SYSTEM_PROMPT = """
            You are a database designer. Given a business description of a MASTER-DETAIL feature
            (e.g. order + order items), output a TWO-table schema in JSON.
            Rules:
            1. moduleName: business domain (system/business/cms/etc.), lowercase
            2. functionName: Chinese feature name (max 10 chars)
            3. main: master table; sub: detail table. Each has:
               tableName (snake_case with domain prefix, e.g. biz_order / biz_order_item),
               businessName (camelCase of core entity, e.g. order / orderItem),
               tableComment, columns
            4. relation.subFkName: the foreign-key column in the SUB table pointing to the
               master id (e.g. order_id); it MUST also appear in sub.columns
            5. columns: array of {columnName, columnType(MySQL), columnComment,
               javaType(String/Integer/Long/BigDecimal/LocalDateTime/Boolean),
               javaField(camelCase), isPk(0|1), isRequired(0|1),
               isQuery(0|1), queryType(EQ|LIKE), htmlType(input|textarea|datetime|switch),
               isList(0|1), isInsert(0|1), isEdit(0|1)}
            6. Do NOT include id/createBy/createTime/updateBy/updateTime/deleted in columns
               (auto-generated). Exception: relation.subFkName column MUST be listed in sub.columns
            7. Optional: a column may carry fkTable/fkValueColumn/fkLabelColumn when it references
               an existing platform table (e.g. sys_dept.id/dept_name) for dropdown binding
            8. isList/isInsert/isEdit default to 1; set 0 for fields that should be hidden
            Output ONLY JSON, no markdown:
            {"moduleName":"...","functionName":"...",
             "main":{"tableName":"...","businessName":"...","tableComment":"...","columns":[...]},
             "sub":{"tableName":"...","businessName":"...","tableComment":"...","columns":[...]},
             "relation":{"subFkName":"..."}}
            """;
    
    /** 树表意图（S54 / tree intent）：LLM 输出单表 + 树三字段；树字段存在性由 TreeIntentValidator 确定性校验 */
    private static final String TREE_SYSTEM_PROMPT = """
            You are a database designer. Given a business description of a TREE/HIERARCHICAL feature
            (e.g. department hierarchy, product category, administrative region), output a single-table
            schema with tree configuration in JSON.
            Rules:
            1. moduleName: business domain (system/business/cms/etc.), lowercase
            2. functionName: Chinese feature name (max 10 chars)
            3. tableName: snake_case with domain prefix (e.g. biz_category)
            4. businessName: camelCase of core entity (e.g. category)
            5. tableComment: brief description
            6. treeCode: the column name used as the unique tree node code (e.g. category_code);
               it MUST also appear in columns
            7. treeParentCode: the column name for parent reference (e.g. parent_id);
               it MUST also appear in columns; root nodes have parent_id = 0
            8. treeName: the column name displayed as the tree node label (e.g. category_name);
               it MUST also appear in columns
            9. columns: array of {columnName, columnType(MySQL), columnComment,
               javaType(String/Integer/Long/BigDecimal/LocalDateTime/Boolean),
               javaField(camelCase), isPk(0|1), isRequired(0|1),
               isQuery(0|1), queryType(EQ|LIKE), htmlType(input|textarea|datetime|switch),
               isList(0|1), isInsert(0|1), isEdit(0|1)}
            10. Do NOT include id/createBy/createTime/updateBy/updateTime/deleted in columns
                (auto-generated). The parent_id column (treeParentCode) MUST be listed in columns.
            11. Optional: a column may carry fkTable/fkValueColumn/fkLabelColumn when it references
                an existing platform table for dropdown binding
            12. isList/isInsert/isEdit default to 1; set 0 for fields that should be hidden
            Output ONLY JSON, no markdown:
            {"moduleName":"...","functionName":"...","tableName":"...","businessName":"...","tableComment":"...",
             "treeCode":"...","treeParentCode":"...","treeName":"...",
             "columns":[...]}
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
        return callLlm(SYSTEM_PROMPT, "Business description: " + description + "\n\nOutput JSON schema:");
    }

    /** Plugin 骨架意图解析（S42）：返回 pluginName/displayName/tablePrefix/moduleDesc */
    public Map<String, Object> parsePluginIntent(String description) {
        log.info("[AI Coding] Plugin intent parse: description={}", description);
        return callLlm(PLUGIN_SYSTEM_PROMPT, "Business domain description: " + description + "\n\nOutput JSON plan:");
    }

    /** 主子表意图解析（S52 / 2.4-F5）：返回 main/sub/relation 结构，闭合性由 SubIntentValidator 校验 */
    public Map<String, Object> parseSubIntent(String description) {
        log.info("[AI Coding] Sub intent parse: description={}", description);
        return callLlm(SUB_SYSTEM_PROMPT, "Master-detail business description: " + description + "\n\nOutput JSON schema:");
    }

    /** 树表意图解析（S54 / tree intent）：返回单表+树三字段，存在性由 TreeIntentValidator 校验 */
    public Map<String, Object> parseTreeIntent(String description) {
        log.info("[AI Coding] Tree intent parse: description={}", description);
        return callLlm(TREE_SYSTEM_PROMPT, "Tree/hierarchical business description: " + description + "\n\nOutput JSON schema:");
    }

    /** 共用：取首个启用供应商 + 首个启用 Key → LLM 调用 → JSON 解析 */
    private Map<String, Object> callLlm(String systemPrompt, String userPrompt) {

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
                .system(systemPrompt)
                .user(userPrompt)
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
