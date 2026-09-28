package com.pivotos.ai.coding.locate;

/**
 * 定位链路的 LLM 调用门面。
 *
 * <p>抽成接口的两个理由：①定位链路的两次调用（粗筛 / 精定位）都要能换模型（S110 决策项：
 * qwen-plus vs qwen3.5-plus 双跑选型）；②集成测试可以注入桩实现，在不联网的前提下覆盖
 * 两段定位与仲裁的全链路（IT 非幂等 + 不烧 token 的硬要求）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public interface LocateLlmClient {

    /**
     * 单轮结构化生成。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param model        模型覆盖（null/空串 = 供应商默认模型）
     * @return 模型原始输出
     */
    String call(String systemPrompt, String userPrompt, String model);

    /**
     * 解析生效模型名（结果回显用，便于双模型对比留证）。
     *
     * @param override 请求级模型覆盖（null/空表示不覆盖）
     * @return 生效模型名
     */
    default String resolveModel(String override) {
        return override == null || override.isBlank() ? "default" : override.trim();
    }
}
