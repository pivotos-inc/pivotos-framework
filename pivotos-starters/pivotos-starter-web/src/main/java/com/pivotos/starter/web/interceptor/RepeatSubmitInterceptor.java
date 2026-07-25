package com.pivotos.starter.web.interceptor;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.web.annotation.RepeatSubmit;
import com.pivotos.starter.web.checker.RepeatSubmitChecker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 幂等拦截器：对标注 @RepeatSubmit 的方法生成请求指纹并委托 Checker 判定
 */
public class RepeatSubmitInterceptor implements HandlerInterceptor {

    private final RepeatSubmitChecker checker;

    public RepeatSubmitInterceptor(RepeatSubmitChecker checker) {
        this.checker = checker;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RepeatSubmit annotation = handlerMethod.getMethodAnnotation(RepeatSubmit.class);
        if (annotation == null) {
            return true;
        }
        String key = buildKey(request);
        if (checker.isRepeatSubmit(key, annotation.interval())) {
            throw new ServiceException(GlobalErrorCode.REPEAT_SUBMIT.getCode(), annotation.message());
        }
        return true;
    }

    /**
     * 请求指纹：登录人（未登录用 IP）+ 方法 + URI + 排序后参数摘要
     */
    private String buildKey(HttpServletRequest request) {
        String user = Optional.ofNullable(LoginContext.getUserId())
                .map(String::valueOf)
                .orElse(request.getRemoteAddr());
        String paramsDigest = request.getParameterMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
                .collect(Collectors.joining("&"));
        return "repeat_submit:" + user + ":" + request.getMethod() + ":" + request.getRequestURI()
                + ":" + Integer.toHexString(paramsDigest.hashCode());
    }
}
