package com.pivotos.docsync.api.enums;

/**
 * 同步执行状态
 */
public enum SyncStatusEnum {

    /** 同步成功 */
    SUCCESS("成功"),

    /** 同步失败 */
    FAILED("失败"),

    /** 同步超时 */
    TIMEOUT("超时"),

    /** 手动导出（仅导出 JSON 供用户手动导入） */
    MANUAL_EXPORT("手动导出"),
    ;

    private final String displayName;

    SyncStatusEnum(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
