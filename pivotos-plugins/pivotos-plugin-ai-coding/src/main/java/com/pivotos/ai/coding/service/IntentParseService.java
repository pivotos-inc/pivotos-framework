package com.pivotos.ai.coding.service;

import com.pivotos.common.core.exception.ServiceException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.ai.api.usage.AiUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * LLM intent parsing: natural language -&gt; table schema JSON.
 * <p>
 * Sends a structured prompt to the LLM and parses the JSON response.
 *
 * <p><b>V3-S1 契约治理</b>：原先本类直接注入 plugin-ai 的 {@code AiClientRegistry} 与两个 Mapper
 * 自行挑选供应商/Key（全仓唯一一条 Plugin 实现层跨模块直调欠款边的一部分）。现在改为只依赖
 * {@link IAiFacade} 的 {@code dynamicChat}——「取启用供应商 + 启用 Key」是实现侧的事，
 * 契约只承诺「走动态通道、没配置就失败」。
 *
 * @author PivotOS
 * @since 2.2.0；V3-S1（v3.0.0）改为走 IAiFacade 契约
 */
@Service
public class IntentParseService {

    private static final Logger log = LoggerFactory.getLogger(IntentParseService.class);

    private final ObjectMapper objectMapper;
    private final IAiFacade aiFacade;

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

    public IntentParseService(ObjectMapper objectMapper, IAiFacade aiFacade) {
        this.objectMapper = objectMapper;
        this.aiFacade = aiFacade;
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

    /**
     * 共用：动态通道 LLM 调用 → JSON 解析。
     *
     * <p>用 {@code dynamicChat} 而非 {@code internalChat}：意图解析<b>不接受静态兜底</b>——
     * 拿不到 Key 时模型输出必然是臆造的表结构，宁可失败也不要假结果。
     * 供应商/Key 的选取由 plugin-ai 负责（V3-S1 契约治理）。
     */
    private Map<String, Object> callLlm(String systemPrompt, String userPrompt) {

        // Intent parse via LLM（S92：coding 场景计量）
        String response = AiUsageContext.callWithScene(AiUsageContext.SCENE_CODING,
                () -> aiFacade.dynamicChat(systemPrompt, userPrompt));

        log.info("[AI Coding] LLM response length={}", response != null ? response.length() : 0);
        if (response == null || response.isBlank()) {
            throw new ServiceException("AI returned empty response");
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
            throw new ServiceException("AI response is not valid JSON", e);
        }
    }
}
