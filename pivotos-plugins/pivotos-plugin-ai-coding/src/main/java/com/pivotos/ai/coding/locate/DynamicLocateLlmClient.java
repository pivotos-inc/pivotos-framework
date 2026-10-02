package com.pivotos.ai.coding.locate;

import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.ai.api.usage.AiUsageContext;
import com.pivotos.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

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
 *       提示词实测返回空数组，内部生成链路一律走契约层的单 system 形态。</li>
 *   <li>S92：AI 用量按场景计量，定位归入 SCENE_CODING。</li>
 * </ul>
 *
 * <p><b>V3-S1 契约治理</b>：原先这里直接注入 plugin-ai 的 {@code AiClientRegistry} 与两个 Mapper，
 * 是全仓唯一一条「Plugin 实现层跨模块直调」的欠款边（13 处 import / 8 个类）。现在改为只依赖
 * {@link IAiFacade}——通道选择、Key 解析、模型装配全部下沉到 plugin-ai，本类只剩「场景计量 + 判空」。
 * 拆微服务时这一处无需任何改动：契约背后是本地 Bean 还是 HTTP 调用，调用方不感知。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）；V3-S1（v3.0.0）改为走 IAiFacade 契约
 */
@Component
public class DynamicLocateLlmClient implements LocateLlmClient {

    private final IAiFacade aiFacade;

    public DynamicLocateLlmClient(IAiFacade aiFacade) {
        this.aiFacade = aiFacade;
    }

    @Override
    public String call(String systemPrompt, String userPrompt, String model) {
        String content;
        try {
            // 场景计量留在调用方：SCENE_CODING 是 ai-coding 的场景，不该由 plugin-ai 替它决定
            content = AiUsageContext.callWithScene(AiUsageContext.SCENE_CODING,
                    () -> aiFacade.internalChat(systemPrompt, userPrompt, model));
        } catch (Exception e) {
            // 对外错误码保持 CODING_LOCATE_FAILED：契约层抛的是 AI_NOT_CONFIGURED / CHAT_FAILED，
            // 但定位链路的调用方（评审页/定位接口）契约上只认这一个码，不能因治理而漂移
            throw new ServiceException(CODING_LOCATE_FAILED);
        }
        if (content == null || content.isBlank()) {
            throw new ServiceException(CODING_LOCATE_FAILED);
        }
        return content;
    }

    @Override
    public String resolveModel(String override) {
        return aiFacade.resolveModel(override);
    }
}
