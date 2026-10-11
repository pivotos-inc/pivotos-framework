package com.pivotos.starter.auth.filter;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.starter.core.context.LoginContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 匿名访问闸门（v3.1.0 生产化 · A4）：路由层兜底，专门治「第三方 jar 自带端点零鉴权」。
 *
 * <p><b>背景</b>：{@code AuthAutoConfiguration} 只注册 SaInterceptor（注解驱动），
 * 而第三方 starter 自带的控制器（如 warm-flow 的 {@code /warm-flow/**}）源码不在本项目内，
 * 加不上 {@code @SaCheckLogin}，编译期 / 启动期 / 单测 / ArchUnit 全部零信号。
 * 本过滤器在路由层按前缀统一要求登录态，不动第三方一行代码。
 *
 * <p><b>登录判定</b>：用 {@link LoginContext}，而不是某个具体 {@code StpLogic}——
 * {@code LoginContextFilter} 已按「管端 → App → 小程序 → 智域」四体系顺序还原登录态，
 * 这里只问「有没有登录」，不关心是哪个体系。代价：本过滤器必须排在
 * {@code LoginContextFilter} 之后（order 更大）。
 *
 * <p><b>响应口径</b>：HTTP 200 + {@code code=1002}（与 {@code @SaCheckLogin} 完全一致）。
 * 刻意不用 401：既有鉴权失败全是 200 + 业务码，前端统一按 body 的 code 分支，
 * 两套语义会让调用方无所适从。
 *
 * <p><b>放行规则</b>：① 不在守卫前缀内；② 在例外前缀内；③ 已登录；④ {@code OPTIONS}
 * 预检（浏览器 CORS 预检不带自定义头，拦了会让跨域调用直接死）。
 */
public class AnonymousAccessGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AnonymousAccessGuardFilter.class);

    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> includePatterns;
    private final List<String> excludePatterns;
    private final String message;
    private final String body;

    public AnonymousAccessGuardFilter(List<String> includePatterns, List<String> excludePatterns, String message) {
        this.includePatterns = includePatterns == null ? new ArrayList<>() : new ArrayList<>(includePatterns);
        this.excludePatterns = excludePatterns == null ? new ArrayList<>() : new ArrayList<>(excludePatterns);
        this.message = (message == null || message.isBlank()) ? "该端点要求登录后访问" : message.trim();
        this.body = "{\"code\":" + GlobalErrorCode.UNAUTHORIZED.getCode()
                + ",\"msg\":\"" + this.message + "\",\"success\":false}";
        log.info("[AUTH][guard] 匿名访问闸门已装配：paths={} exclude={}", this.includePatterns, this.excludePatterns);
    }

    /** 守卫前缀（供测试与排障核对，不含例外） */
    public List<String> getIncludePatterns() {
        return List.copyOf(includePatterns);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = resolvePath(request);
        if (!matches(includePatterns, path)) {
            return true;
        }
        return matches(excludePatterns, path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // OPTIONS 预检不带 Authorization，一律放行（真实请求仍会被守）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (LoginContext.getUserId() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("[AUTH][guard] 拦截匿名访问：{} {}", request.getMethod(), resolvePath(request));
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    /** 去掉 context-path 后的路径，避免部署在非根路径时前缀匹配失效 */
    private String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private boolean matches(List<String> patterns, String path) {
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
