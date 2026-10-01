package com.pivotos.starter.datainspect.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * 数据监控域错误码：82xx 段（81xx 已由搜索域占用）。
 *
 * <p>号段分配：
 * <ul>
 *   <li>8200-8209 参数与组件</li>
 *   <li>8210-8219 安全闸门（SQL 拒绝）</li>
 *   <li>8220-8229 执行与降级</li>
 * </ul>
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public enum DataInspectErrorCode implements ErrorCode {

    // ---------- 参数与组件 ----------
    COMPONENT_INVALID(8200, "数据监控组件类型不合法"),
    COMPONENT_NOT_FOUND(8201, "数据监控组件未装配"),
    PARAM_INVALID(8202, "数据监控请求参数不合法"),

    // ---------- 安全闸门 ----------
    SQL_REJECTED(8210, "SQL 被安全闸门拒绝"),
    SQL_MULTI_STATEMENT(8211, "不接受多语句：一次只能执行一条语句"),
    SQL_NOT_SELECT(8212, "只允许 SELECT 语句"),
    SQL_TABLE_FORBIDDEN(8213, "语句引用了不在白名单内的库表"),
    SQL_FUNCTION_FORBIDDEN(8214, "语句使用了被禁止的函数"),
    SQL_TOO_MANY_ROWS(8215, "返回行数超过上限"),
    SQL_TIMEOUT(8216, "查询超时，已熔断"),

    // ---------- 执行与降级 ----------
    COMPONENT_UNREACHABLE(8220, "数据监控组件不可达"),
    EXECUTE_FAILED(8221, "数据监控执行失败"),
    ;

    private final int code;
    private final String msg;

    DataInspectErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
