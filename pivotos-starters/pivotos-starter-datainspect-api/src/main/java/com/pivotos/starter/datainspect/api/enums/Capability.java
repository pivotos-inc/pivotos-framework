package com.pivotos.starter.datainspect.api.enums;

/**
 * 数据监控能力集：前端按能力裁剪 UI（例如 Redis 无 QUERY，则整块 SQL 输入区不渲染）。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public enum Capability {

    /** 列举库 / 索引分组 / Redis db */
    LIST_SCHEMAS("列举库"),
    /** 列举表 / 索引 / key */
    LIST_TABLES("列举表"),
    /** 分页预览（内部固定语句，不接受用户语句） */
    PREVIEW("预览"),
    /** 自由 SQL / DSL 执行（高危，需 monitor:data:query） */
    QUERY("自由查询"),
    /** 统计信息（行数 / 存储 / TTL） */
    STATS("统计"),
    ;

    private final String label;

    Capability(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
