package com.pivotos.ai.coding.locate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.ai.api.usage.AiUsageContext;
import com.pivotos.ai.client.AiClientRegistry;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.mapper.AiApiKeyMapper;
import com.pivotos.ai.mapper.AiProviderMapper;
import com.pivotos.common.core.exception.ServiceException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_LOCATE_FAILED;

/**
 * 定位链路 LLM 客户端（动态供应商优先，静态兜底）。
 *
 * <p>三条既有口径在此落实：
 * <ul>
 *   <li>双通道：与对话链路（AiChatServiceImpl）同款——先取「启用供应商 + 启用 Key」动态通道，
 *       取不到再回落到 {@code spring.ai.openai.*} 静态兜底 ChatClient。dev 库当前
 *       {@code ai_api_key} 为空，定位链路正是走静态兜底才跑得通。</li>
 *   <li>S96 K7：DashScope 兼容端对「defaultSystem + 调用方 system」双 system 形态的结构化
 *       提示词实测返回空数组，内部生成链路一律改用 {@code getInternalChatClient} 单 system 形态。</li>
 *   <li>S92：AI 用量按场景计量，定位归入 SCENE_CODING。</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Component
public class DynamicLocateLlmClient implements LocateLlmClient {

    private final AiClientRegistry registry;
    private final AiProviderMapper providerMapper;
    private final AiApiKeyMapper keyMapper;
    private final ObjectProvider<ChatClient> staticClientProvider;

    public DynamicLocateLlmClient(AiClientRegistry registry,
                                  AiProviderMapper providerMapper,
                                  AiApiKeyMapper keyMapper,
                                  ObjectProvider<ChatClient> staticClientProvider) {
        this.registry = registry;
        this.providerMapper = providerMapper;
        this.keyMapper = keyMapper;
        this.staticClientProvider = staticClientProvider;
    }

    @Override
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
        var spec = (provider != null && key != null)
                ? buildDynamic(provider, key, systemPrompt, userPrompt, override ? model : null)
                : buildStatic(systemPrompt, userPrompt, override ? model : null);
        if (spec == null) {
            throw new ServiceException(CODING_LOCATE_FAILED);
        }
        var finalSpec = spec;
        String content = AiUsageContext.callWithScene(AiUsageContext.SCENE_CODING, () -> finalSpec.call().content());
        if (content == null || content.isBlank()) {
            throw new ServiceException(CODING_LOCATE_FAILED);
        }
        return content;
    }

    @Override
    public String resolveModel(String override) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        List<AiProvider> providers = providerMapper.selectList(
                new LambdaQueryWrapper<AiProvider>().eq(AiProvider::getStatus, 0));
        return providers.isEmpty() ? "static" : providers.get(0).getDefaultModel();
    }

    /** 动态通道：供应商 + Key 装配，模型覆盖按 provider.code 分派工厂产出 options */
    private ChatClient.ChatClientRequestSpec buildDynamic(AiProvider provider, AiApiKey key,
                                                          String systemPrompt, String userPrompt, String model) {
        var spec = registry.getInternalChatClient(provider, key)
                .prompt()
                .system(systemPrompt)
                .user(userPrompt);
        return model == null ? spec : spec.options(registry.buildChatOptions(provider, model));
    }

    /** 静态兜底：spring.ai.openai.* 装配的 ChatClient，模型覆盖走 OpenAI options */
    private ChatClient.ChatClientRequestSpec buildStatic(String systemPrompt, String userPrompt, String model) {
        ChatClient client = staticClientProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        var spec = client.prompt().system(systemPrompt).user(userPrompt);
        // Spring AI 2.0 的 spec.options() 收 Builder 本体（内部与 client 默认 options 合并），不收 build() 结果
        return model == null ? spec : spec.options(OpenAiChatOptions.builder().model(model));
    }
}
