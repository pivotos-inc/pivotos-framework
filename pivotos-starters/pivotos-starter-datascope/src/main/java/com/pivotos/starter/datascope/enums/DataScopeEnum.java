package com.pivotos.starter.datascope.enums;

/**
 * 数据权限范围枚举
 *
 * @author PivotOS Team
 */
public enum DataScopeEnum {

    /** 全部数据权限 */
    ALL(1, "全部数据权限"),

    /** 本部门 */
    DEPT(2, "本部门"),

    /** 本部门及以下 */
    DEPT_AND_BELOW(3, "本部门及以下"),

    /** 仅本人 */
    SELF(4, "仅本人"),

    /** 自定义部门 */
    CUSTOM(5, "自定义部门");

    private final Integer code;
    private final String desc;

    DataScopeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static DataScopeEnum fromCode(Integer code) {
        if (code == null) {
            return ALL;
        }
        for (DataScopeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return ALL;
    }
}
