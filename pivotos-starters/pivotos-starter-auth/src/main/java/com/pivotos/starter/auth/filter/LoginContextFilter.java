package com.pivotos.starter.auth.filter;

import cn.dev33.satoken.servlet.util.SaTokenContextJakartaServletUtil;
import cn.dev33.satoken.stp.StpLogic;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.auth.account.StpAppUtil;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.auth.account.StpWxMiniUtil;
import com.pivotos.starter.auth.support.AuthSessionHolder;
import com.pivotos.starter.core.context.LoginContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 登录上下文绑定过滤器：识别请求所属账号体系，
 * 从 Token 会话还原 LoginUser，用 ScopedValue 包裹整条请求链。
 * 未登录请求直接放行（由 @SaCheckLogin 等注解做鉴权拦截）。
 * <p>注意：本过滤器执行早于 Sa-Token 上下文初始化点，
 * 因此先用 SaTokenContextServletUtil 显式初始化，链尾清理。
 */
public class LoginContextFilter extends OncePerRequestFilter {

    /** 账号体系识别顺序：管理端 → App → 小程序 */
    private static final List<StpLogic> ACCOUNT_LOGICS = List.of(
        StpSysUtil.STP, StpAppUtil.STP, StpWxMiniUtil.STP);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        SaTokenContextJakartaServletUtil.setContext(request, response);
        try {
            LoginUser loginUser = resolveLoginUser();
            if (loginUser == null) {
                doChain(request, response, filterChain);
                return;
            }
            ScopedValue.where(LoginContext.KEY, loginUser)
                .run(() -> doChain(request, response, filterChain));
        } finally {
            SaTokenContextJakartaServletUtil.clearContext();
        }
    }

    private void doChain(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            throw new IllegalStateException("LoginContextFilter 请求链执行失败", e);
        }
    }

    /**
     * 按体系顺序识别登录态，还原 LoginUser
     */
    private LoginUser resolveLoginUser() {
        for (StpLogic logic : ACCOUNT_LOGICS) {
            Object loginId = logic.getLoginIdDefaultNull();
            if (loginId == null) {
                continue;
            }
            Object stored = logic.getTokenSession().get(AuthSessionHolder.LOGIN_USER_KEY);
            if (stored instanceof LoginUser loginUser) {
                return loginUser;
            }
            // 会话数据缺失（如 Redis 被清）时的兜底重建
            return new LoginUser(Long.valueOf(loginId.toString()), null, logic.getLoginType(), null);
        }
        return null;
    }
}
