package com.pivotos.starter.datainspect.api.enums;

/**
 * 数据监控组件不可用原因码（照抄 SearchUnavailableReason 口径：文案集中放枚举，不散写中文）。
 *
 * <p>契约：<b>任何不可用形态都返回 200 + code=0 + available=false + reason，绝不抛异常、绝不 500</b>。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public enum RejectReason {

    NOT_ENABLED("数据监控能力未启用（pivotos.datainspect.enabled=false）"),
    QUERY_DISABLED("自由查询已关闭（pivotos.datainspect.query-enabled=false），仅可浏览与预览"),
    IMPL_MISSING("未引入该组件的实现模块"),
    UNREACHABLE("组件不可达"),
    COLLECT_FAILED("采集失败"),
    UNSUPPORTED("该组件不支持此操作"),
    FORBIDDEN("语句被安全闸门拒绝"),
    ;

    private final String text;

    RejectReason(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /** 带明细的展示文案 */
    public String text(String detail) {
        if (detail == null || detail.isBlank()) {
            return text;
        }
        return text + "（" + detail + "）";
    }
}
