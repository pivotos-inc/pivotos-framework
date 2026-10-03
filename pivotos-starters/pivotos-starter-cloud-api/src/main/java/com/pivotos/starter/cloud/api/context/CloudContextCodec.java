package com.pivotos.starter.cloud.api.context;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.core.context.TraceContext;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 上下文传播编解码器：三通道<b>共用</b>的唯一实现。
 *
 * <p>出站（客户端侧）：{@link #capture()} 从 ScopedValue 上下文门面读出快照，
 * {@link #toHeaders(CloudContext)} 序列化成请求头，由各通道自己的拦截器写出去
 * （local 走 JDK HttpClient、cloud 走 Feign RequestInterceptor、Gateway 走 Filter）。
 *
 * <p>入站（服务端侧）：{@link #fromHeaders(Function)} 反序列化，
 * {@link #runWith(CloudContext, Runnable)} 用 ScopedValue 包裹后续链路完成绑定。
 *
 * <p><b>为什么不用 ThreadLocal</b>：项目上下文体系是 ScopedValue（虚拟线程天然安全），
 * ArchUnit A7 禁止 starter 层出现 ThreadLocal 字段，且 ThreadLocal 在虚拟线程下会泄漏。
 */
public final class CloudContextCodec {

    private CloudContextCodec() {
    }

    /**
     * 出站：捕获当前进程上下文快照。
     * 任何一方为 null（未登录 / 单租户 / 无链路）都不写对应头，避免把 "null" 传出去。
     */
    public static CloudContext capture() {
        LoginUser user = LoginContext.get();
        return new CloudContext(
            TenantContext.get(),
            user == null ? null : user.getUserId(),
            user == null ? null : user.getUsername(),
            user == null ? null : user.getAccountType(),
            TraceContext.get());
    }

    /** 出站：快照 → 请求头（null 字段不出现） */
    public static Map<String, String> toHeaders(CloudContext ctx) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (ctx == null) {
            return headers;
        }
        put(headers, CloudHeaders.TENANT_ID, ctx.getTenantId());
        put(headers, CloudHeaders.USER_ID, ctx.getUserId());
        put(headers, CloudHeaders.USERNAME, ctx.getUsername());
        put(headers, CloudHeaders.ACCOUNT_TYPE, ctx.getAccountType());
        put(headers, CloudHeaders.TRACE_ID, ctx.getTraceId());
        return headers;
    }

    /** 入站：请求头 → 快照（缺失/空串一律为 null） */
    public static CloudContext fromHeaders(Function<String, String> headerGetter) {
        if (headerGetter == null) {
            return CloudContext.EMPTY;
        }
        return new CloudContext(
            parseLong(headerGetter.apply(CloudHeaders.TENANT_ID)),
            parseLong(headerGetter.apply(CloudHeaders.USER_ID)),
            blankToNull(headerGetter.apply(CloudHeaders.USERNAME)),
            blankToNull(headerGetter.apply(CloudHeaders.ACCOUNT_TYPE)),
            blankToNull(headerGetter.apply(CloudHeaders.TRACE_ID)));
    }

    /** 入站：从 Servlet 请求解析 */
    public static CloudContext fromRequest(HttpServletRequest request) {
        return fromHeaders(request == null ? null : request::getHeader);
    }

    /**
     * 入站：用 ScopedValue 包裹任务，把快照绑定为当前上下文。
     *
     * <p>只绑定快照中<b>非空</b>的项：局部恢复（如只带租户）不会把登录态清成 null。
     * 绑定顺序无关（ScopedValue 各自独立作用域）。
     *
     * <p>{@link LoginContext#KEY} 的常规绑定入口是 starter-auth；此处是跨进程恢复的
     * 唯一例外，调用方（{@link CloudContextFilter}）必须先过内部调用校验才允许带上身份。
     */
    public static void runWith(CloudContext ctx, Runnable task) {
        if (ctx == null || ctx.isEmpty()) {
            task.run();
            return;
        }
        Runnable body = task;
        if (ctx.getUserId() != null || ctx.getUsername() != null) {
            LoginUser user = new LoginUser(ctx.getUserId(), ctx.getUsername(), ctx.getAccountType(), ctx.getTenantId());
            Runnable prev = body;
            body = () -> ScopedValue.where(LoginContext.KEY, user).run(prev);
        }
        if (ctx.getTenantId() != null) {
            Long tenantId = ctx.getTenantId();
            Runnable prev = body;
            body = () -> ScopedValue.where(TenantContext.KEY, tenantId).run(prev);
        }
        if (ctx.getTraceId() != null) {
            String traceId = ctx.getTraceId();
            Runnable prev = body;
            body = () -> ScopedValue.where(TraceContext.KEY, traceId).run(prev);
        }
        body.run();
    }

    /**
     * 内部调用校验：请求头 {@code X-PivotOS-Internal} 与期望凭证常量时间比较。
     * 期望值未配置（空）时一律 false —— 没配凭证就不许恢复身份，这是默认值的安全含义。
     */
    public static boolean isInternalCall(HttpServletRequest request, String expectedToken) {
        if (request == null || expectedToken == null || expectedToken.isBlank()) {
            return false;
        }
        String actual = request.getHeader(CloudHeaders.INTERNAL_TOKEN);
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
            actual.getBytes(StandardCharsets.UTF_8),
            expectedToken.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(Map<String, String> headers, String key, Object value) {
        if (value != null) {
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                headers.put(key, text);
            }
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
