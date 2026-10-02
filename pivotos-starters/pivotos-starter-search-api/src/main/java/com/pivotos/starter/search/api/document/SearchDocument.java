package com.pivotos.starter.search.api.document;

import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 搜索文档（写入侧）。
 * <p>Provider 层只认「索引 + 文档 ID + 扁平 source」，不做实体映射——
 * 实体 ↔ Map 的转换统一由 {@link com.pivotos.starter.search.api.template.SearchTemplate} 完成，
 * 这样新增实现只需处理最薄的文档语义。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
@Builder
public final class SearchDocument {

    /** 索引名 */
    private final String indexName;

    /** 文档 ID（雪花 ID 字符串，同项目主键口径） */
    private final String id;

    /** 文档内容（扁平字段） */
    private final Map<String, Object> source;

    public static SearchDocument of(String indexName, String id, Map<String, Object> source) {
        return SearchDocument.builder()
                .indexName(indexName)
                .id(id)
                .source(source == null ? new HashMap<>() : new HashMap<>(source))
                .build();
    }

    /**
     * 返回防御性副本，避免调用方持有引用后改动索引内的内容
     */
    public Map<String, Object> copySource() {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }
}
