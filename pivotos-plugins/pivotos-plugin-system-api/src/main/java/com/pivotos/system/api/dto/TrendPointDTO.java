package com.pivotos.system.api.dto;

import lombok.Data;

/**
 * 按日趋势点 DTO（跨 Plugin 契约，趋势图横轴日期 + 纵轴计数，S71）。
 */
@Data
public class TrendPointDTO {

    /** 日期（yyyy-MM-dd） */
    private String date;

    /** 当日计数 */
    private Long value;

    public TrendPointDTO() {
    }

    public TrendPointDTO(String date, Long value) {
        this.date = date;
        this.value = value;
    }
}
