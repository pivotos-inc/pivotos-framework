package com.pivotos.common.core.enums;

/**
 * 通用状态枚举
 */
public enum CommonStatusEnum {

    /** 启用 */
    ENABLED(0, "启用"),
    /** 停用 */
    DISABLED(1, "停用"),
    ;

    private final int value;
    private final String label;

    CommonStatusEnum(int value, String label) {
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
