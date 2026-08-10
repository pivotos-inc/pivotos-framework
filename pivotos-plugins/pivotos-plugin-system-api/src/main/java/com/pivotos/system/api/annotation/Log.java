package com.pivotos.system.api.annotation;

import com.pivotos.system.api.enums.OperType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解（S26 2.1-F1）：标注在 Controller 方法上，
 * 由 system 插件 OperLogAspect 切面采集写入 sys_oper_log。
 * 入参摘要自动脱敏（password/token 类字段不落库）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /** 功能模块（如"通知公告"） */
    String module();

    /** 操作类型 */
    OperType type();

    /** 是否记录入参摘要（默认记录，敏感接口可关闭） */
    boolean recordParams() default true;
}
