package com.pivotos.ai.client;

import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * 供应商对话模型工厂：按 {@code provider.code} 分派装配各家 SDK 的 ChatModel。
 *
 * <p>设计红线（S45 用户拍板）：对外暴露的数据结构/行为以 OpenAI 兼容模式为基准，
 * 供应商协议差异（鉴权头、base-url 版本段、模型列表响应结构）全部在本适配层消化，
 * 对话/落库/SSE/健康度链路不感知供应商差异。
 *
 * <p>未识别的 code 一律回落 OpenAI 兼容工厂（{@link #isFallback()} 实现），
 * 即现状 dashscope 等 OpenAI 兼容供应商行为不变，向后兼容。
 */
public interface ChatModelFactory {

    /**
     * 是否负责该供应商 code（大小写不敏感）。
     * 兜底工厂恒返回 true，由注册表最后兜底使用。
     */
    boolean supports(String code);

    /** 是否 OpenAI 兼容兜底工厂（未识别 code 的回落目标） */
    default boolean isFallback() {
        return false;
    }

    /**
     * 构建对话模型：base-url / api-key / 默认模型全部收敛进各 SDK 的 options，
     * 实际对话时由 {@link #buildChatOptions(String)} 按请求覆盖模型。
     */
    ChatModel buildChatModel(AiProvider provider, AiApiKey key);

    /**
     * 构建 per-request options（仅模型覆盖）：Spring AI 2.0 {@code spec.options(Builder)}
     * 内部与 client 默认 options 合并，返回上转型以屏蔽各家 options 类型差异。
     */
    ChatOptions.Builder<?> buildChatOptions(String model);

    /**
     * 拉取供应商可用模型 ID 列表（升序）。
     * 各家鉴权头与响应结构差异在此消化，统一返回模型 ID 字符串列表；
     * 失败直接抛异常，由 AiClientRegistry 统一转 5023。
     */
    List<String> listModels(AiProvider provider, AiApiKey key);
}
