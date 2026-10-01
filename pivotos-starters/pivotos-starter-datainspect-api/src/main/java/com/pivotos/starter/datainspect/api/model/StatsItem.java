package com.pivotos.starter.datainspect.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表 / 索引 / key 的统计信息。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsItem {

    private String schema;

    private String table;

    /** 行数 / 文档数 / 元素数；-1 表示未知 */
    private long rowCount;

    /** 存储字节；-1 表示未知 */
    private long sizeBytes;

    /** 引擎 / 索引健康 / key 类型 */
    private String engine;

    /** 组件特有补充项（如 ES 分片数、Redis 编码方式） */
    private Map<String, Object> extra;

    public Map<String, Object> safeExtra() {
        return extra == null ? new LinkedHashMap<>() : extra;
    }
}
