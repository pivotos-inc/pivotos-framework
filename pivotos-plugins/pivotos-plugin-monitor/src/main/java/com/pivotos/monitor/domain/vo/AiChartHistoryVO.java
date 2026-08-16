package com.pivotos.monitor.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 图表历史视图（S83）：列表与回放共用。
 */
@Data
public class AiChartHistoryVO {

    private Long id;

    /** 生成时的自然语言描述 */
    private String question;

    /** 图表标题 */
    private String title;

    /** 图表类型：line / bar / pie */
    private String chartType;

    /** ChartSpec JSON（前端回放时解析，按 chartType 确定性装配） */
    private String specJson;

    private LocalDateTime createTime;
}
