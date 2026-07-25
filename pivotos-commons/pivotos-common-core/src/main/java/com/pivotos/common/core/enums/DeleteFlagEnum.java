package com.pivotos.common.core.enums;

/**
 * 逻辑删除标记枚举
 */
public enum DeleteFlagEnum {

    /** 正常 */
    NORMAL(0, "正常"),
    /** 已删除 */
    DELETED(1, "已删除"),
    ;

    private final int value;
    private final String label;

    DeleteFlagEnum(int value, String label) {
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
