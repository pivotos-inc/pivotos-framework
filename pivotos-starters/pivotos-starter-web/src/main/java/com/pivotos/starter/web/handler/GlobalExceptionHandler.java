package com.pivotos.starter.web.handler;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.TraceContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：所有异常统一转换为 R，并回填 traceId。
 * 业务异常抛 ServiceException，未知异常兜底 1500 并记录完整堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public R<Void> handleServiceException(ServiceException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return fill(R.fail(e.getCode(), e.getMessage()));
    }

    /**
     * @RequestBody 对象参数校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = Optional.ofNullable(e.getBindingResult().getFieldError())
                .map(FieldError::getDefaultMessage)
                .orElse(GlobalErrorCode.PARAM_INVALID.getMsg());
        return fill(R.fail(GlobalErrorCode.PARAM_INVALID.getCode(), msg));
    }

    /**
     * 表单对象绑定失败
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return fill(R.fail(GlobalErrorCode.PARAM_INVALID.getCode(),
                msg.isBlank() ? GlobalErrorCode.PARAM_INVALID.getMsg() : msg));
    }

    /**
     * 方法级 @Validated 单参数校验失败
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("；"));
        return fill(R.fail(GlobalErrorCode.PARAM_INVALID.getCode(),
                msg.isBlank() ? GlobalErrorCode.PARAM_INVALID.getMsg() : msg));
    }

    /**
     * 请求本身不合法（缺参 / 报文不可读 / 方法不支持 / 媒体类型不支持 / 类型转换失败）
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            MethodArgumentTypeMismatchException.class})
    public R<Void> handleBadRequest(Exception e) {
        log.warn("请求不合法: {}", e.getMessage());
        return fill(R.fail(GlobalErrorCode.PARAM_INVALID));
    }

    /**
     * 未知异常兜底：对外只暴露统一文案，堆栈只进日志
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleUnknown(Exception e) {
        log.error("系统内部错误", e);
        return fill(R.fail(GlobalErrorCode.SYSTEM_ERROR));
    }

    private R<Void> fill(R<Void> r) {
        r.setTraceId(TraceContext.get());
        return r;
    }
}
