package com.pivotos.ai.api.dto;

import lombok.Data;

/**
 * AI 按日趋势点 DTO（跨 Plugin 契约，S71）。
 */
@Data
public class AiTrendPointDTO {

    /** 日期（yyyy-MM-dd） */
    private String date;

    /** 当日计数 */
    private Long value;

    public AiTrendPointDTO() {
    }

    public AiTrendPointDTO(String date, Long value) {
        this.date = date;
        this.value = value;
    }
}
