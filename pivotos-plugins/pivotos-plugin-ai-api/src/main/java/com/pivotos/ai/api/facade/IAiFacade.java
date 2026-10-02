package com.pivotos.ai.api.facade;

import com.pivotos.ai.api.dto.AiChatStatsDTO;

/**
 * ai 插件对外契约：给其他插件的最小 AI 能力入口（摘要/生成类场景）。
 * 多轮会话、流式输出属于端上交互，走 plugin-ai 的 Controller，不进契约。
 */
public interface IAiFacade {

    /**
     * 单轮文本生成（同步阻塞）
     *
     * @param prompt 用户提示词
     * @return 模型回复文本
     */
    String chat(String prompt);

    /**
     * 带 system 提示的单轮文本生成（同步阻塞，动态 Key 体系）
     *
     * <p>与 {@link #chat(String)} 的区别：走首个启用供应商 + 首个启用 Key 的动态解析链，
     * 适用于结构化推断类场景（S72 AI 生成图表等）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型回复文本
     */
    String chatWithSystem(String systemPrompt, String userPrompt);

    /**
     * AI 运营统计（会话/消息/供应商/Key 健康 + 近 days 日消息趋势，S71 运营工作台/数据大屏）
     *
     * @param days 趋势天数
     */
    AiChatStatsDTO chatStats(int days);

    /**
     * 内部生成链路专用对话（V3-S1 契约治理新增）：<b>动态通道优先，静态兜底</b>。
     *
     * <p>与 {@link #chatWithSystem(String, String)} 的区别：后者在没有启用供应商/Key 时直接失败，
     * 而本方法会回落到 {@code spring.ai.openai.*} 静态 ChatClient——内部链路（代码定位、编排规划）
     * 在 dev/未配 Key 的环境下必须能跑通，因此兜底是这条契约的一部分。
     *
     * <p>口径（S96 K7）：内部链路一律走 <b>单 system 形态</b>，不用 defaultSystem + 调用方 system 的双 system 形态。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param model        模型覆盖（null/空白表示取供应商默认模型或静态通道默认模型）
     * @return 模型回复原文（可能为 null/空，由调用方决定是否判空）
     */
    String internalChat(String systemPrompt, String userPrompt, String model);

    /**
     * 动态通道对话（V3-S1 契约治理新增）：<b>必须走动态 Key 体系，无静态兜底</b>。
     *
     * <p>适用于「没有 AI 配置就不该继续」的场景（如意图解析：拿不到 Key 时输出必然是臆造的结构化结果）。
     * 与 {@link #internalChat(String, String, String)} 的差异是<b>契约语义</b>，不是实现细节——
     * 调用方按「能不能接受兜底」二选一，别混用。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型回复原文
     */
    String dynamicChat(String systemPrompt, String userPrompt);

    /**
     * 解析生效模型名（V3-S1 契约治理新增）
     *
     * @param modelOverride 调用方指定模型（null/空白表示不覆盖）
     * @return 覆盖值本身；未覆盖时为首个启用供应商的默认模型，无启用供应商时返回 {@code "static"}
     */
    String resolveModel(String modelOverride);
}
