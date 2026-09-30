package com.pivotos.ai.orchestrator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.api.usage.AiUsageContext;
import com.pivotos.ai.client.AiClientRegistry;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.mapper.AiApiKeyMapper;
import com.pivotos.ai.mapper.AiProviderMapper;
import com.pivotos.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 编排规划链路的 LLM 客户端（A5-1 / S116）——动态供应商优先，静态兜底。
 *
 * <p>三条既有口径在此落实（同 S110 的 {@code DynamicLocateLlmClient}）：
 * <ul>
 *   <li><b>双通道</b>：先取「首个启用供应商 + 该供应商首个启用 Key」动态通道，
 *       取不到再回落 {@code spring.ai.openai.*} 静态兜底 ChatClient。
 *       dev 库 {@code ai_api_key} 当前无可用行（15 行全是 S115 遗留的 deleted=1），
 *       编排规划正是靠静态兜底跑得通。</li>
 *   <li><b>S96 K7</b>：DashScope 兼容端对「defaultSystem + 调用方 system」双 system 形态的
 *       结构化提示词实测返回空数组，内部生成链路一律改单 system 形态。</li>
 *   <li><b>S92</b>：AI 用量按场景计量；编排规划一次意图只发一轮请求，量级极小，
 *       在独立场景注册前先沿用 {@link AiUsageContext#SCENE_CHAT}，避免为它新增一套场景常量。</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.15.0（S116 A5-1）
 */
@Component
public class PlanDraftLlmClient {

    private static final Logger log = LoggerFactory.getLogger(PlanDraftLlmClient.class);

    private final AiClientRegistry registry;
    private final AiProviderMapper providerMapper;
    private final AiApiKeyMapper keyMapper;
    private final ObjectProvider<ChatClient> staticClientProvider;

    public PlanDraftLlmClient(AiClientRegistry registry,
                              AiProviderMapper providerMapper,
                              AiApiKeyMapper keyMapper,
                              ObjectProvider<ChatClient> staticClientProvider) {
        this.registry = registry;
        this.providerMapper = providerMapper;
        this.keyMapper = keyMapper;
        this.staticClientProvider = staticClientProvider;
    }

    /**
     * 单轮结构化生成。
     *
     * @param systemPrompt 系统提示（工具目录 + plan 契约）
     * @param userPrompt   用户意图
     * @param model        模型覆盖（空表示取供应商默认模型）
     * @return 模型输出原文
     */
    public String call(String systemPrompt, String userPrompt, String model) {
        List<AiProvider> providers = providerMapper.selectList(
                new LambdaQueryWrapper<AiProvider>().eq(AiProvider::getStatus, 0));
        AiProvider provider = providers.isEmpty() ? null : providers.get(0);
        AiApiKey key = null;
        if (provider != null) {
            List<AiApiKey> keys = keyMapper.selectList(
                    new LambdaQueryWrapper<AiApiKey>()
                            .eq(AiApiKey::getProviderId, provider.getId())
                            .eq(AiApiKey::getStatus, 0));
            key = keys.isEmpty() ? null : keys.get(0);
        }
        boolean override = model != null && !model.isBlank();
        ChatClient.ChatClientRequestSpec spec = null;
        if (provider != null && key != null) {
            log.debug("[PivotOS] 编排规划走动态通道：provider={}", provider.getCode());
            spec = registry.getInternalChatClient(provider, key)
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt);
            if (override) {
                spec = spec.options(registry.buildChatOptions(provider, model));
            }
        } else {
            ChatClient client = staticClientProvider.getIfAvailable();
            if (client != null) {
                log.debug("[PivotOS] 编排规划走静态兜底通道");
                spec = client.prompt().system(systemPrompt).user(userPrompt);
                if (override) {
                    spec = spec.options(OpenAiChatOptions.builder().model(model));
                }
            }
        }
        if (spec == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        ChatClient.ChatClientRequestSpec finalSpec = spec;
        try {
            String content = AiUsageContext.callWithScene(AiUsageContext.SCENE_CHAT, () -> finalSpec.call().content());
            // S96 K7 实证：DashScope 兼容端在「defaultSystem + 调用方 system」双 system 形态下会返回空内容，
            // 且是静默失败（HTTP 200、content 为空串）——不打印原文就永远定位不到
            int len = content == null ? -1 : content.length();
            log.info("[PivotOS] 编排规划 LLM 返回：len={} 全文={}", len, abbreviate(content, 4000));
            if (content == null || content.isBlank()) {
                log.warn("[PivotOS] 编排规划返回空内容（多为双 system 形态或模型侧空转）");
                throw new ServiceException(AiErrorCode.ORCHESTRATOR_PLAN_EMPTY);
            }
            return content;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PivotOS] 编排规划失败", e);
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_PLAN_EMPTY);
        }
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "<null>";
        }
        String flat = value.replace("\n", "\\n");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
