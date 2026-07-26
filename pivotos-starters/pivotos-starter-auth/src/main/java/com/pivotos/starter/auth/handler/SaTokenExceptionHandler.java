package com.pivotos.starter.auth.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 鉴权异常统一转换：
 * 未登录 → 1002；无权限 → 1003（S8 启动验证清单的 403 统一体）。
 */
@RestControllerAdvice
public class SaTokenExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SaTokenExceptionHandler.class);

    /**
     * 未登录 / Token 失效
     */
    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLogin(NotLoginException e) {
        log.warn("未认证访问: {}", e.getMessage());
        return fill(R.fail(GlobalErrorCode.UNAUTHORIZED));
    }

    /**
     * 无权限 / 无角色
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public R<Void> handleNotPermission(Exception e) {
        log.warn("越权访问: {}", e.getMessage());
        return fill(R.fail(GlobalErrorCode.FORBIDDEN));
    }

    private R<Void> fill(R<Void> r) {
        r.setTraceId(TraceContext.get());
        return r;
    }
}
