package com.pivotos.system.aspect;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.filter.ValueFilter;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.api.annotation.Log;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.service.OperLogService;
import com.pivotos.system.support.ServletUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;

/**
 * 操作日志切面（S26 2.1-F1）：拦截 @Log 注解方法，采集模块/操作类型/
 * 入参摘要（脱敏）/耗时/结果写入 sys_oper_log。
 * 上下文取 LoginContext（ScopedValue 门面，同线程直读，禁 ThreadLocal）。
 * 日志落库失败只告警不影响业务主流程。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperLogAspect {

    /** 入参摘要截断上限（列宽 2048，留余量） */
    private static final int PARAMS_MAX_LENGTH = 2000;

    /** 异常信息截断上限 */
    private static final int ERROR_MAX_LENGTH = 512;

    /** 脱敏字段名关键词（小写包含匹配，命中不落库） */
    private static final String[] SENSITIVE_KEYS = {"password", "pwd", "secret", "token", "apikey", "api_key"};

    /** fastjson2 序列化级脱敏：敏感字段值替换为 ***（密码类字段不落库） */
    private static final ValueFilter MASK_FILTER = (object, name, value) -> {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String key : SENSITIVE_KEYS) {
            if (lower.contains(key)) {
                return "***";
            }
        }
        return value;
    };

    private final OperLogService operLogService;

    @Around("@annotation(logAnn)")
    public Object around(ProceedingJoinPoint joinPoint, Log logAnn) throws Throwable {
        long start = System.currentTimeMillis();
        Throwable error = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            error = ex;
            throw ex;
        } finally {
            record(joinPoint, logAnn, System.currentTimeMillis() - start, error);
        }
    }

    /** 采集并落库（同步写，单条 insert 开销可忽略；失败只告警） */
    private void record(ProceedingJoinPoint joinPoint, Log logAnn, long duration, Throwable error) {
        try {
            SysOperLog entity = new SysOperLog();
            entity.setModule(logAnn.module());
            entity.setOperType(logAnn.type().getLabel());
            LoginUser user = LoginContext.get();
            if (user != null) {
                entity.setOperUserId(user.getUserId());
                entity.setOperName(user.getUsername());
            }
            entity.setMethod(joinPoint.getSignature().getDeclaringType().getSimpleName()
                    + "." + joinPoint.getSignature().getName());
            HttpServletRequest request = ServletUtils.getRequest();
            if (request != null) {
                entity.setRequestMethod(request.getMethod());
                entity.setRequestUrl(request.getRequestURI());
            }
            if (logAnn.recordParams()) {
                entity.setRequestParams(summarizeParams(joinPoint.getArgs()));
            }
            entity.setStatus(error == null ? 0 : 1);
            if (error != null) {
                String msg = String.valueOf(error.getMessage());
                entity.setErrorMsg(msg.length() > ERROR_MAX_LENGTH ? msg.substring(0, ERROR_MAX_LENGTH) : msg);
            }
            entity.setDuration(duration);
            entity.setOperTime(LocalDateTime.now());
            operLogService.saveLog(entity);
        } catch (Exception ex) {
            log.warn("[oper-log] 操作日志记录失败：{}", ex.getMessage());
        }
    }

    /** 入参摘要：过滤 Servlet/文件类参数 → fastjson2 脱敏序列化 → 截断 */
    private String summarizeParams(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object[] loggable = Arrays.stream(args)
                .filter(arg -> !(arg instanceof HttpServletRequest)
                        && !(arg instanceof HttpServletResponse)
                        && !(arg instanceof MultipartFile))
                .toArray();
        if (loggable.length == 0) {
            return null;
        }
        try {
            String json = JSON.toJSONString(loggable.length == 1 ? loggable[0] : loggable, MASK_FILTER);
            return json.length() > PARAMS_MAX_LENGTH ? json.substring(0, PARAMS_MAX_LENGTH) : json;
        } catch (Exception ex) {
            return "[序列化失败]";
        }
    }
}
