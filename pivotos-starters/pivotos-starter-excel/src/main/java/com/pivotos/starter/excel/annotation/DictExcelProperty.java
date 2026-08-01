package com.pivotos.starter.excel.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字典 Excel 属性注解：标记 EasyExcel DTO 字段所属字典类型，导出时 value→label，导入时 label→value。
 *
 * <p>用法示例：
 * <pre>{@code
 * @DictExcelProperty(dictType = "sys_user_sex")
 * @ExcelProperty("性别")
 * private String gender;
 * }</pre>
 *
 * @author PivotOS Team
 * @since 2.1.0
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DictExcelProperty {

    /** 字典类型（如 sys_user_sex、sys_normal_disable） */
    String dictType();

    /** 导出时是否翻译：true=写 label 到 Excel，false=保留原始 value（默认 true） */
    boolean translate() default true;
}
