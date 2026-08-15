package com.pivotos.monitor.domain.dto;

import lombok.Data;

/**
 * AI 生成图表请求（S72）
 */
@Data
public class AiChartRequest {

    /** 自然语言描述（如「登录趋势和 AI 消息趋势画一张对比折线图」） */
    private String question;
}
