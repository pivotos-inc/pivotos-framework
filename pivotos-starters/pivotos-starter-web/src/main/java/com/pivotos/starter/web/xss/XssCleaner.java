package com.pivotos.starter.web.xss;

import java.util.regex.Pattern;

/**
 * XSS 清洗器：剥离脚本标签 / 危险协议 / 内联事件。
 * 富文本场景需放行时请使用独立的富文本接口并做服务端白名单过滤，勿全局放开。
 */
public final class XssCleaner {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("<\\s*script[^>]*>.*?<\\s*/\\s*script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("<\\s*/?\\s*(script|iframe|object|embed|frameset|form)[^>]*>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("on\\w+\\s*=", Pattern.CASE_INSENSITIVE),
    };

    private XssCleaner() {
    }

    /**
     * 清洗输入，null 安全
     */
    public static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = value;
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll("");
        }
        return result;
    }
}
