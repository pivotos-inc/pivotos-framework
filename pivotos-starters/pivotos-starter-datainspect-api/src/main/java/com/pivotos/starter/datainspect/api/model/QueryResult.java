package com.pivotos.starter.datainspect.api.model;

import com.pivotos.starter.datainspect.api.enums.RejectReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一查询结果。
 *
 * <p>降级口径（照抄 ES 监控）：组件不可用时 {@code available=false} + {@code reason} 有值、rows 为空，
 * <b>绝不抛异常</b>，由 Controller 原样返回 200 + code=0。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResult {

    private List<ColumnItem> columns;

    private List<Map<String, Object>> rows;

    /** 总条数；-1 表示未知（Redis / ES 预览不查总数） */
    private long total;

    /** 是否被行数上限或 value 截断 */
    private boolean truncated;

    /** 耗时（毫秒） */
    private long durationMs;

    /** 透明化提示：已注入 LIMIT / 已按租户改写 / 已脱敏 N 列 */
    private List<String> warnings;

    private boolean available;

    private String reasonCode;

    private String reason;

    public static QueryResult unavailable(RejectReason reason, String detail) {
        return QueryResult.builder()
                .columns(new ArrayList<>())
                .rows(new ArrayList<>())
                .total(0)
                .durationMs(0)
                .warnings(new ArrayList<>())
                .available(false)
                .reasonCode(reason.name())
                .reason(reason.text(detail))
                .build();
    }
}
