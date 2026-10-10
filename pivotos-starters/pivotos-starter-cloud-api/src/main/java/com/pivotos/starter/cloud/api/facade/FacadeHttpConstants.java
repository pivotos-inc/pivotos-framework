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

    /** 实现体抛异常（不把业务异常原文回抛到网络上，只回形态） */
    public static final String ERROR_INVOCATION = "INVOCATION_FAILED";

    private FacadeHttpConstants() {
    }

    /** 成功响应体 */
    public static String ok(ObjectMapper mapper, Object data) {
        return write(mapper, true, data, null);
    }

    /** 失败响应体 */
    public static String fail(ObjectMapper mapper, String error) {
        return write(mapper, false, null, error);
    }

    private static String write(ObjectMapper mapper, boolean ok, Object data, String error) {
        try {
            return mapper.writeValueAsString(new Envelope(ok, data, error));
        } catch (Exception e) {
            // 序列化失败就别再尝试序列化异常本身：给一个固定串，避免错误被二次吞掉
            return ok ? "{\"ok\":true,\"data\":\"<unserializable>\"}"
                : "{\"ok\":false,\"error\":\"" + ERROR_INVOCATION + "\"}";
        }
    }

    /** 响应外壳：{@code {"ok":bool,"data":T,"error":String}} */
    public record Envelope(boolean ok, Object data, String error) {
    }
}
