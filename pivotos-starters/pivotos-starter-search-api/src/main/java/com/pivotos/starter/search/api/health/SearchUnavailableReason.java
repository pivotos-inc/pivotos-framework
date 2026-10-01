package com.pivotos.starter.search.api.health;

/**
 * ES / 搜索健康快照不可用的原因。
 * <p>与 {@code SearchErrorCode} 同口径：<b>文案集中放在枚举里</b>，不在业务代码里散写中文，
 * 便于统一维护与将来接 i18n。{@link SearchHealthSnapshot#getReasonCode()} 存 {@link #name()}，
 * 前端/日志可按 code 判定，{@link SearchHealthSnapshot#getReason()} 是可直接展示的文案。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public enum SearchUnavailableReason {

    /** 搜索 Starter 未启用（pivotos.search.enabled=false）或压根没装配 */
    NOT_ENABLED("未启用搜索能力（pivotos.search.enabled=false），无 ES 集群可监控"),
    /** 当前生效实现就是 simple 内存实现（配置如此，不算故障） */
    SIMPLE_IMPL("当前搜索实现为 simple 内存实现，未连接 Elasticsearch"),
    /** 配了 ES 实现，但实现模块没引入 / 启动期自检未通过，已回落 simple */
    FALLBACK("已配置 Elasticsearch 实现，但当前未生效（实现模块未引入或启动期自检未通过），已回落 simple 内存实现"),
    /** 实现已生效，但采集指标时 ES 不可达或返回异常 */
    COLLECT_FAILED("ES 指标采集失败（连接不可达或服务端返回异常）"),
    ;

    private final String text;

    SearchUnavailableReason(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /** 带细节的文案（细节为空时只返回原文案） */
    public String text(String detail) {
        if (detail == null || detail.isBlank()) {
            return text;
        }
        return text + "：" + detail;
    }
}
