package com.pivotos.starter.search.api.core;

import com.pivotos.starter.search.api.annotation.SearchIndex;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;

import java.util.Locale;

/**
 * 索引名解析：{@code @SearchIndex} 显式声明优先，否则类名转 kebab-case 小写。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class SearchIndexNameResolver {

    /** 索引名合法字符（ES 硬性要求：小写 + 数字 + . _ -，且不能以 - _ + 开头） */
    private static final String VALID = "^[a-z0-9][a-z0-9._-]*$";

    private SearchIndexNameResolver() {
    }

    public static String resolve(Class<?> type) {
        SearchIndex annotation = type.getAnnotation(SearchIndex.class);
        String name = (annotation == null || annotation.value().isBlank())
                ? toKebab(type.getSimpleName())
                : annotation.value().trim().toLowerCase(Locale.ROOT);
        requireValid(name);
        return name;
    }

    /**
     * 应用全局索引前缀（多环境共用一套 ES 时隔离）
     */
    public static String applyPrefix(String indexName, String prefix) {
        String name = indexName == null ? "" : indexName.trim();
        if (prefix == null || prefix.isBlank()) {
            return name;
        }
        return prefix.trim().toLowerCase(Locale.ROOT) + "_" + name;
    }

    public static void requireValid(String indexName) {
        if (indexName == null || !indexName.matches(VALID)) {
            throw new SearchException(SearchErrorCode.INDEX_NAME_INVALID, String.valueOf(indexName));
        }
    }

    /**
     * SysOperLog → sys-oper-log
     */
    private static String toKebab(String simpleName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
