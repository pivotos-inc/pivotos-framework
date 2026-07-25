package com.pivotos.starter.web.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重复提交注解：标注在 Controller 方法上。
 * 需要容器中存在 RepeatSubmitChecker 实现（由 starter-redis 提供）才生效。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatSubmit {

    /**
     * 幂等窗口（毫秒），窗口内相同请求判定为重复提交
     */
    long interval() default 5000;

    /**
     * 重复提交提示
     */
    String message() default "请求重复提交，请稍后重试";
}
