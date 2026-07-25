package com.pivotos.starter.core.json;

import com.pivotos.common.core.sensitive.SensitiveType;

/**
 * 脱敏工具
 */
public final class SensitiveMasker {

    private SensitiveMasker() {
    }

    /**
     * 按类型脱敏
     */
    public static String mask(String value, SensitiveType type) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (type) {
            case ALL -> "*".repeat(value.length());
            case NAME -> value.length() <= 1 ? value : value.charAt(0) + "*".repeat(value.length() - 1);
            case MOBILE -> maskMiddle(value, 3, 4);
            case ID_CARD, BANK_CARD -> maskMiddle(value, 4, 4);
            case EMAIL -> maskEmail(value);
        };
    }

    private static String maskMiddle(String value, int head, int tail) {
        if (value.length() <= head + tail) {
            return "*".repeat(value.length());
        }
        return value.substring(0, head)
                + "*".repeat(value.length() - head - tail)
                + value.substring(value.length() - tail);
    }

    private static String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 1) {
            return at >= 0 ? "*" + value.substring(at) : "*".repeat(value.length());
        }
        return value.charAt(0) + "***" + value.substring(at);
    }
}
