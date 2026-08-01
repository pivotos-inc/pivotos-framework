package com.pivotos.ai.coding.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * AI Coding 请求
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Data
public class CodingRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 自然语言描述 */
    private String description;
}
