package com.pivotos.system.datascope;

import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.datascope.context.DataScopeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 数据权限上下文绑定过滤器。
 * <p>
 * 在 LoginContextFilter（+20）和 TenantContextFilter（+30）之后执行（+50），
 * 解析当前用户的数据权限并通过 ScopedValue 绑定到整个请求链。
 * 后续 MyBatis 查询中 DataPermissionInterceptor 从上下文中读取权限信息。
 *
 * @author PivotOS Team
 */
@Component
@RequiredArgsConstructor
public class DataScopeBindingFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(DataScopeBindingFilter.class);

    private final DataScopeHelper dataScopeHelper;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) {
        var loginUser = LoginContext.get();
        if (loginUser == null) {
            // 未登录：绑定跳过标记
            ScopedValue.where(DataScopeContext.KEY, DataScopeContext.DataScopeInfo.skip())
                    .run(() -> doChain(request, response, filterChain));
            return;
        }

        // 解析数据权限
        DataScopeContext.DataScopeInfo scope = dataScopeHelper.resolve();

        // 通过 ScopedValue 绑定到整个请求链
        ScopedValue.where(DataScopeContext.KEY, scope != null ? scope :
                        DataScopeContext.DataScopeInfo.skip())
                .run(() -> doChain(request, response, filterChain));
    }

    private void doChain(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            throw new IllegalStateException("DataScopeBindingFilter 请求链执行失败", e);
        }
    }
}
