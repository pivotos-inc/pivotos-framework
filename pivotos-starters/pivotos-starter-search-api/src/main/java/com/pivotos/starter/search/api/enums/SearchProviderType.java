package com.pivotos.starter.search.api.enums;

import java.util.Arrays;
import java.util.Locale;

/**
 * 搜索实现类型（{@code pivotos.search.type}）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public enum SearchProviderType {

    /** 内存兜底实现：零中间件，进程内检索，重启即失（同 vector-store 的 simple 口径） */
    SIMPLE("simple"),
    /** Easy-ES 实现（easy-es-core 3.0.2，内嵌 elasticsearch-java 7.17.28，面向 ES 7.17） */
    EASY_ES("easy-es"),
    /** 官方 elasticsearch-java 实现（8.19.x，面向 ES 8.x/9.x） */
    ES_JAVA("es-java");

    private final String code;

    SearchProviderType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 按配置值解析（大小写/空格不敏感）。
     *
     * @return 命中枚举；未命中返回 {@code null}（由调用方决定回退策略，不在此处擅自兜底）
     */
    public static SearchProviderType of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(t -> t.code.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
