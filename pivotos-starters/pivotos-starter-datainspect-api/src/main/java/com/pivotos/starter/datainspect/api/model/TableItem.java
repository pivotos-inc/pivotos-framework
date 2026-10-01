package com.pivotos.starter.datainspect.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表 / 索引 / Redis key。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableItem {

    /** 所属库 / 索引分组 / db */
    private String schema;

    /** 表名 / 索引名 / key 名 */
    private String name;

    /** 类型：table / view / index / string / hash / list / set / zset */
    private String type;

    /** 备注（表注释 / 索引健康 / key 类型说明） */
    private String comment;

    /** 估算条数（表行数 / 文档数 / 集合元素数；-1 表示未知） */
    private long rowCount;

    /** Redis TTL（秒；-1 表示无过期 / 不适用） */
    private long ttl;
}
