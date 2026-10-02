package com.pivotos.starter.datainspect.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结果列描述。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnItem {

    private String name;

    /** 数据库类型名（动态查询场景下可能为 null） */
    private String type;

    /** 是否被敏感字段脱敏规则命中 */
    private boolean masked;
}
