package com.pivotos.starter.job.retention;

import java.util.List;

/**
 * 一批待删主键 + 待删总数。
 *
 * @param ids    本轮实际取到的主键（长度 ≤ 传入的 size）
 * @param total  符合条件的待删总行数（由分页查询的 count 得到；大于 ids 数量表示本轮删不完）
 * @author PivotOS
 * @since 3.0.0
 */
public record RetentionIdBatch(List<Long> ids, long total) {

    public RetentionIdBatch {
        ids = ids == null ? List.of() : List.copyOf(ids);
        total = Math.max(total, ids.size());
    }
}
