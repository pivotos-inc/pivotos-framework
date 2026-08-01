package com.pivotos.starter.web.handler;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.TraceContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：所有异常统一转换为 R，并回填 traceId。
 * 业务异常抛 ServiceException，未知异常兜底 1500 并记录完整堆栈。
 * 统一用 ResponseEntity 显式声明 application/json：SSE 端点（Accept:
 * text/event-stream）流建立前抛异常时，若交给内容协商会因 Accept 无交集
 * 失败成 500 空 body，前端整包 JSON 回退分支永远拿不到 R.msg（S23 实测）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<R<Void>> handleServiceException(ServiceException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return fill(R.fail(e.getCode(), e.getMessage()));
    }

    /**
     * @RequestBody 对象参数校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = Optional.ofNullable(e.getBindingResult().getFieldError())
                .map(FieldError::getDefaultMessage)
                .orElse(GlobalErrorCode.PARAM_INVALID.getMsg());
        return fill(R.fail(GlobalErrorCode.PARAM_INVALID.getCode(), msg));
    }

    /**
     * 表单对象绑定失败
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(BindException e) {
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
    public ResponseEntity<R<Void>> handleConstraintViolation(ConstraintViolationException e) {
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
    public ResponseEntity<R<Void>> handleBadRequest(Exception e) {
        log.warn("请求不合法: {}", e.getMessage());
        return fill(R.fail(GlobalErrorCode.PARAM_INVALID));
    }

    /**
     * 上传文件大小超出限制
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大，超出限制: {} bytes", e.getMaxUploadSize());
        return fill(R.fail(GlobalErrorCode.FILE_TOO_LARGE));
    }

    /**
     * 静态资源/路由不存在（如直接访问后端地址或 SPA 路由打到 Spring）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return fill(R.fail(GlobalErrorCode.NOT_FOUND));
    }

    /**
     * 未知异常兜底：对外只暴露统一文案，堆栈只进日志
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("系统内部错误", e);
        return fill(R.fail(GlobalErrorCode.SYSTEM_ERROR));
    }

    private ResponseEntity<R<Void>> fill(R<Void> r) {
        r.setTraceId(TraceContext.get());
        // 显式 Content-Type 跳过 Accept 协商（SSE 等非 JSON Accept 场景也能拿到 R 体）
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(r);
    }
}
