package com.pivotos.starter.job.client;

/**
 * XXL-Job Open API 调用结果。
 * <p>
 * 调度中心不可达或认证失败时返回 {@code fail(msg)}，不抛异常。
 *
 * @author PivotOS
 * @since 2.10.0
 */
public record XxlJobResult(boolean success, String msg, Integer jobId) {

    public static XxlJobResult ok(Integer jobId) {
        return new XxlJobResult(true, null, jobId);
    }

    public static XxlJobResult ok() {
        return new XxlJobResult(true, null, null);
    }

    public static XxlJobResult fail(String msg) {
        return new XxlJobResult(false, msg, null);
    }
}
