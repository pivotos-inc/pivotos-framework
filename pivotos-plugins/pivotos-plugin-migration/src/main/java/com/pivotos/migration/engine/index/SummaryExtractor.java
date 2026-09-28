package com.pivotos.migration.engine.index;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件摘要抽取：取首个「有效注释」作为索引摘要（粗定位语料的一行描述）。
 *
 * <p>S107 K5 实证：javadoc 多行注释的 {@code /**} 开行与 {@code *} 续行都要匹配，否则索引里
 * 大半文件摘要为空（首轮 spike 就是这么烧掉一遍 token 的）。本类按该口径同时匹配三种注释形态。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public final class SummaryExtractor {

    /** 注释行：//、/* *、* 续行、&lt;!-- */
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*(?:/\\*{1,2}|\\*|//|<!--)\\s?(.*)$");

    private static final int MAX_LENGTH = 60;

    private SummaryExtractor() {
    }

    /**
     * 抽取摘要（跳过纯注解行 {@code @xxx} 与空行），无注释返回空串。
     */
    public static String extract(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        for (String line : content.split("\n", 200)) {
            Matcher m = COMMENT_PATTERN.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String text = m.group(1).trim();
            if (text.isEmpty() || text.startsWith("@")) {
                continue;
            }
            text = text.replace("*/", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        }
        return "";
    }
}
