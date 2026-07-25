package com.pivotos.common.core.enums;

/**
 * 性别枚举
 */
public enum GenderEnum {

    /** 未知 */
    UNKNOWN(0, "未知"),
    /** 男 */
    MALE(1, "男"),
    /** 女 */
    FEMALE(2, "女"),
    ;

    private final int value;
    private final String label;

    GenderEnum(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public int getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }
}
