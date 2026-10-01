package com.pivotos.starter.datainspect.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库 / 索引分组 / Redis db。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaItem {

    /** 库名 / ES 固定为 "indices" / Redis 为 "db0" 形态 */
    private String name;

    /** 展示名 */
    private String label;

    /** 表/索引/key 数量（-1 表示未知） */
    private long itemCount;
}
