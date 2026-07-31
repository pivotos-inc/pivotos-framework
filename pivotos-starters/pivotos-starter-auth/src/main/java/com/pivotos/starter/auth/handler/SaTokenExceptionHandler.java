package com.pivotos.starter.auth.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 鉴权异常统一转换：
 * 未登录 → 1002；无权限 → 1003（S8 启动验证清单的 403 统一体）。
 * 统一用 ResponseEntity 显式声明 application/json：SSE 端点（Accept:
 * text/event-stream）流建立前抛异常时，若交给内容协商会因 Accept 无交集
 * 失败成 500 空 body，前端整包 JSON 回退分支永远拿不到 R.msg（S23 实测）。
 */
@RestControllerAdvice
public class SaTokenExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SaTokenExceptionHandler.class);

    /**
     * 未登录 / Token 失效
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLogin(NotLoginException e) {
        log.warn("未认证访问: {}", e.getMessage());
        return fill(R.fail(GlobalErrorCode.UNAUTHORIZED));
    }

    /**
     * 无权限 / 无角色
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<R<Void>> handleNotPermission(Exception e) {
        log.warn("越权访问: {}", e.getMessage());
        return fill(R.fail(GlobalErrorCode.FORBIDDEN));
    }

    private ResponseEntity<R<Void>> fill(R<Void> r) {
        r.setTraceId(TraceContext.get());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(r);
    }
}
