package com.pivotos.ai.facade;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * IAiFacade 本地实现（单体形态）
 *
 * <p>其他 Plugin 只注入 IAiFacade 契约；无状态单轮生成，不落会话/消息表。
 */
@Component
@RequiredArgsConstructor
public class AiLocalFacade implements IAiFacade {

    private static final Logger log = LoggerFactory.getLogger(AiLocalFacade.class);

    /** ChatClient 可能不存在（api-key 未配置），懒获取 + 5020 兜底 */
    private final ObjectProvider<ChatClient> chatClientProvider;

    @Override
    public String chat(String prompt) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }
        try {
            return chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.error("[PivotOS] AI 单轮生成失败", e);
            throw new ServiceException(AiErrorCode.CHAT_FAILED);
        }
    }
}
