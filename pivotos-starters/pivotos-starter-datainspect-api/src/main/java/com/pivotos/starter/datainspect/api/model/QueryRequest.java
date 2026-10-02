package com.pivotos.starter.datainspect.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自由查询请求（高危，需 {@code monitor:data:query}）。
 *
 * <p>statement 对用户完全自由，<b>因此必须过安全闸门</b>：权限只决定能不能看到输入框，
 * 闸门决定能不能真的执行，二者不可互相替代。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRequest {

    private String component;

    /** MySQL 可空（语句里自带库名）；Redis 无此概念 */
    private String schema;

    /** 自由语句（MySQL 为 SQL；ES 为 JSON DSL；Redis 不支持） */
    private String statement;

    /** 期望返回行数，会被 hard-max-rows 收紧 */
    private Integer maxRows;
}
