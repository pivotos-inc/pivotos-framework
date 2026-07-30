package com.pivotos.ai.api.facade;

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
}
