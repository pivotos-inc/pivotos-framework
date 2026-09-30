package com.pivotos.starter.search.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明实体对应的搜索索引名；缺省时按「类名转 kebab-case」推断。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SearchIndex {

    /**
     * 索引名（小写，建议下划线或中划线分隔）
     */
    String value();
}
