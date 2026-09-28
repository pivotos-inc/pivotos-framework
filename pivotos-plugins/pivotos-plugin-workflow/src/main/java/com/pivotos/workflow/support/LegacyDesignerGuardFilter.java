package com.pivotos.workflow.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * D1 旧内置设计器退役守卫（v2.14.0）。
 * <p>
 * 背景：warm-flow 内置 jar 设计器（{@code warm-flow-plugin-ui-sb-web}，挂载 {@code /warm-flow-ui/}）
 * 自 S103 起与 bpmn-js 新版设计器并存灰度（14 号清单决策：并存灰度至 v2.14.0 下线）。
 * 本守卫是「入口摘除 + 配置开关兜底」的落地件：默认拦截旧入口全部请求（静态页与 UI 接口），
 * jar 依赖保留不剔除（剔除留 v2.15.0 再议），需要回滚时置
 * {@code pivotos.workflow.legacy-designer.enabled=true} 即恢复并存态。
 * <p>
 * 只拦 UI（{@code /warm-flow-ui}），不拦引擎 API（{@code /warm-flow/**}）——
 * 新版设计器的 save-json / query-def 链路仍依赖引擎 API，二者必须分开对待。
 * <p>
 * 响应口径：HTTP 410 Gone + 业务码 4100（flow 域 4xxx 段，语义「入口已下线」）。
 */
public class LegacyDesignerGuardFilter extends OncePerRequestFilter {

    /** 旧入口已下线响应体（code=4100，与 flow 域错误码段一致） */
    private static final String RETIRED_BODY =
            "{\"code\":4100,\"msg\":\"旧内置设计器已于 v2.14.0 下线，请使用新版流程设计器\",\"success\":false}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setStatus(HttpServletResponse.SC_GONE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(RETIRED_BODY);
    }
}
