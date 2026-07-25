package com.pivotos.starter.mybatis.crypto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级加密注解：标注在 DO 的 String 字段上，
 * 由 FieldEncryptTypeHandler 在读写库时透明加解密（AES）。
 * SM4 算法预留，P1 引入 BouncyCastle 后补齐。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldEncrypt {
}
