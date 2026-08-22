package com.pivotos.ai.enums;

/**
 * AI 工具类型（S98 A2）
 *
 * <p>read 只读工具默认放行登录用户；write 写操作工具须显式声明，
 * 配合 confirm_required 走二次确认预检协议（confirm=true 才真实执行）。
 */
public enum ToolType {

    /** 只读类（查询/聚合，无副作用） */
    READ("read"),

    /** 写操作类（发通知/催办等有副作用操作） */
    WRITE("write");

    private final String code;

    ToolType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
