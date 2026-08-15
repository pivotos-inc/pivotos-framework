package com.pivotos.monitor.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * AI 生成图表规格 VO（S72）：LLM 只输出结构化 ChartSpec，
 * ECharts option 由前端按 chartType 确定性装配（禁直出 raw option，规避 formatter 注入面）。
 */
@Data
public class AiChartSpecVO {

    /** 图表标题 */
    private String title;

    /** 图表类型：line / bar / pie（白名单校验） */
    private String chartType;

    /** 类目轴数据（line/bar 必填，pie 忽略） */
    private List<String> categories;

    /** 数据系列（pie 取首个系列） */
    private List<Series> series;

    /** 一句话说明（解释图表内容与数据来源） */
    private String explanation;

    @Data
    public static class Series {

        /** 系列名称 */
        private String name;

        /** 数值序列（与 categories 等长；pie 为各类目数值） */
        private List<Double> data;
    }
}
