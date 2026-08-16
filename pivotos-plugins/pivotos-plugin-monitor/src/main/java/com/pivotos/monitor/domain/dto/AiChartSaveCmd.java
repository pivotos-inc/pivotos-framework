package com.pivotos.monitor.domain.dto;

import com.pivotos.monitor.domain.vo.AiChartSpecVO;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * AI 图表保存命令（S83）：收藏当前生成的 ChartSpec。
 */
@Data
public class AiChartSaveCmd {

    /** 生成时的自然语言描述（可选，便于历史回溯） */
    @Schema(description = "生成时的自然语言描述（可选，便于历史回溯）")
    private String question;

    /** 图表规格（与生成端点同结构，前端原样回传） */
    @Schema(description = "图表规格（与生成端点同结构，前端原样回传）")
    private AiChartSpecVO spec;
}
