package com.pivotos.system.api.enums;

/** 操作日志操作类型（sys_oper_log.oper_type 落库用 label） */
public enum OperType {

    CREATE("新增"),
    UPDATE("修改"),
    DELETE("删除"),
    PUBLISH("发布"),
    REVOKE("撤回"),
    EXPORT("导出"),
    IMPORT("导入"),
    /** S130 数据监控：自由 SQL / 预览执行（高危，需 monitor:data:query / :preview） */
    QUERY("查询"),
    OTHER("其他");

    private final String label;

    OperType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
