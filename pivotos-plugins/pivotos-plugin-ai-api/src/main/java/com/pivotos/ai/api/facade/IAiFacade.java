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
}
