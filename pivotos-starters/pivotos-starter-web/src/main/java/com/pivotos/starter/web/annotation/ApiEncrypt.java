package com.pivotos.starter.web.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口加解密注解：标注在 Controller 方法上。
 * 仅 pivotos.encrypt.enabled=true 时生效（默认关闭，联调分阶段开启）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiEncrypt {

    /**
     * 是否解密请求体
     */
    boolean decryptRequest() default true;

    /**
     * 是否加密响应体
     */
    boolean encryptResponse() default true;
}
