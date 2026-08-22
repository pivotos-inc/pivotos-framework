package com.pivotos.ai.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * AI 用量汇总 VO（S92 用量监控页头部卡片 + 场景分布 + 日趋势）
 */
@Data
public class AiUsageSummaryVO {

    /** 调用总次数 */
    private Long calls;

    /** 失败调用次数 */
    private Long failedCalls;

    /** 提示词 token 合计 */
    private Long promptTokens;

    /** 生成 token 合计 */
    private Long completionTokens;

    /** 总 token 合计 */
    private Long totalTokens;

    /** 按场景分布 */
    private List<SceneItem> byScene;

    /** 按日趋势（缺日补 0） */
    private List<TrendItem> trend;

    @Data
    public static class SceneItem {
        /** 场景（chat/rag/coding/chart/embedding 相关/other） */
        private String scene;
        /** 调用次数 */
        private Long calls;
        /** token 合计 */
        private Long totalTokens;
    }

    @Data
    public static class TrendItem {
        /** 日期 yyyy-MM-dd */
        private String day;
        /** 调用次数 */
        private Long calls;
        /** token 合计 */
        private Long totalTokens;
    }
}
