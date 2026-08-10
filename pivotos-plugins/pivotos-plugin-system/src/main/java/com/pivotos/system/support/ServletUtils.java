package com.pivotos.system.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Servlet 请求信息工具（日志采集用，非 Web 上下文一律返回 null） */
public final class ServletUtils {

    private ServletUtils() {
    }

    /** 当前请求，非 Web 上下文返回 null */
    public static HttpServletRequest getRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    /** 客户端 IP：X-Forwarded-For 首段优先（Nginx 反代场景），回落 remoteAddr */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && !"unknown".equalsIgnoreCase(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /** 浏览器 UA（截断防超列宽） */
    public static String getUserAgent(HttpServletRequest request, int maxLength) {
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return null;
        }
        return ua.length() > maxLength ? ua.substring(0, maxLength) : ua;
    }
}
