package com.pivotos.starter.cloud.api.facade;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Facade HTTP 端点的 wire 常量与响应封装。
 *
 * <p><b>为什么自己持 {@code ObjectMapper}（Jackson 2）而不复用容器的消息转换器</b>：
 * 容器里主用的是 Jackson 3（Boot 4 世代），反射调用的参数/返回值反序列化在 Jackson 2 上最稳，
 * 且两者包名不同可共存（与 {@code starter-cloud-local} 的 {@code JsonSupport} 同款做法）。
 * 自己持有还能让端点不依赖任何具体的 HttpMessageConverter 实现。
 */
public final class FacadeHttpConstants {

    /** 默认端点基路径 */
    public static final String BASE_PATH = "/__facade";

    /** 内部凭证头缺失/不匹配 */
    public static final String ERROR_FORBIDDEN = "FORBIDDEN";

    /** 接口不在白名单 / 本进程无实现（不区分两者，避免被拿来探测哪些类存在） */
    public static final String ERROR_NOT_FOUND = "NOT_FOUND";

    /** 找不到方法 */
    public static final String ERROR_NO_METHOD = "NO_METHOD";

    /** 参数个数超限 */
    public static final String ERROR_TOO_MANY_ARGS = "TOO_MANY_ARGS";

    /** 参数无法反序列化成目标形参类型（形态错，不是业务拒绝） */
    public static final String ERROR_BAD_ARGS = "BAD_ARGS";

    /**
     * 业务异常（{@code ServiceException}）：HTTP 仍为 200。
     *
     * <p><b>为什么业务异常走 200 而不是 4xx/5xx</b>：与 local 通道的
     * {@code FacadeRpcServlet} 同口径 —— 业务拒绝是「调用成功了、对方按规则拒绝了」，
     * 不是传输失败。把 {@code 非本人待办} 打成一个 500，调用方只能当成「下游挂了」去重试，
     * 而重试永远也不会成功。
     */
    public static final String ERROR_SERVICE = "SERVICE_EXCEPTION";

    /** 实现体抛非业务异常（不把业务异常原文回抛到网络上，只回形态） */
    public static final String ERROR_INVOCATION = "INVOCATION_FAILED";

    private FacadeHttpConstants() {
    }

    /** 成功响应体 */
    public static String ok(ObjectMapper mapper, Object data) {
        return write(mapper, new Envelope(true, data, null, null, null));
    }

    /** 失败响应体（无业务码） */
    public static String fail(ObjectMapper mapper, String error) {
        return write(mapper, new Envelope(false, null, error, null, null));
    }

    /**
     * 业务异常响应体：{@code ok=false} + {@code error=SERVICE_EXCEPTION} + 业务码与文案。
     *
     * <p>回传 message 是刻意的：跨进程调用方拿不到「为什么被拒」就没法区分
     * 「该重试」与「该放弃」。但只回 {@code ServiceException} 的码与文案，
     * 其它异常一律不回原文（防堆栈泄漏）。
     */
    public static String serviceFail(ObjectMapper mapper, int code, String message) {
        return write(mapper, new Envelope(false, null, ERROR_SERVICE, code,
            message == null || message.isBlank() ? null : message));
    }

    private static String write(ObjectMapper mapper, Envelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            // 序列化失败就别再尝试序列化异常本身：给一个固定串，避免错误被二次吞掉
            return envelope.ok()
                ? "{\"ok\":true,\"data\":\"<unserializable>\"}"
                : "{\"ok\":false,\"error\":\"" + ERROR_INVOCATION + "\"}";
        }
    }

    /**
     * 响应外壳：{@code {"ok":bool,"data":T,"error":String,"code":Integer,"message":String}}。
     *
     * <p>{@code code} / {@code message} 只在业务异常分支出现（{@code @JsonInclude(NON_NULL)}），
     * 因此 403 / 404 / 500 这些基础设施分支的响应体与 V3-S3 时期<b>逐字节一致</b>，
     * 既有的 {@code FacadeHttpExposureTest} 断言不需要改。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Envelope(boolean ok, Object data, String error, Integer code, String message) {
    }
}
