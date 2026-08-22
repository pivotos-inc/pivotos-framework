package com.pivotos.ai.filter;

import com.alibaba.fastjson2.JSON;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * MCP 端点登录防护过滤器（S98 A2，清偿 S97 K5 欠账）。
 *
 * <p>spring-ai-starter-mcp-server-webmvc 的 GET /sse 与 POST /mcp/message
 * 是 Servlet 层端点，不走 MVC 注解鉴权（SaInterceptor 拦不到），S97 实测匿名可达。
 * 本过滤器强制登录态：未登录一律 401；已登录请求经 LoginContextFilter（排序在前）
 * 绑定 LoginContext 后，工具调用即可带上调用人身份走角色白名单与审计。
 *
 * <p>MCP 客户端接入姿势：/sse 与 /mcp/message 两个请求均须携带 Authorization
 * 头（与登录 token 同值）。
 */
public class McpEndpointAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpEndpointAuthFilter.class);

    /** 未登录响应码（对齐全局 401 口径） */
    private static final int UNAUTHORIZED = 401;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (LoginContext.getUserId() == null) {
            log.warn("[PivotOS] MCP 端点匿名访问被拒：{} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(JSON.toJSONString(R.fail(UNAUTHORIZED, "MCP 端点需登录后访问")));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
