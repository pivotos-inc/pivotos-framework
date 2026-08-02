package com.pivotos.generator.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GeneratorServiceImpl private utility methods via reflection.
 * <p>
 * Covers: toClassName, toCamelCase, toJavaType, inferQueryType, inferHtmlType
 */
@DisplayName("Generator utility methods")
class GeneratorUtilTest {

    private static final Class<GeneratorServiceImpl> TARGET = GeneratorServiceImpl.class;

    /** Instance created via no-arg construction (dependencies left null - not needed for utility methods) */
    private GeneratorServiceImpl instance;

    @BeforeEach
    void setUp() throws Exception {
        java.lang.reflect.Constructor<GeneratorServiceImpl> ctor = TARGET.getDeclaredConstructor();
        ctor.setAccessible(true);
        instance = ctor.newInstance();
    }

    // ==================== toClassName ====================

    @ParameterizedTest
    @CsvSource({
            "sys_user, SysUser",
            "biz_product, BizProduct",
            "order_item_detail, OrderItemDetail",
            "t_user, TUser",
            "log, Log",
    })
    @DisplayName("toClassName - table name to PascalCase class name")
    void testToClassName(String tableName, String expected) throws Exception {
        assertEquals(expected, invoke("toClassName", tableName));
    }

    @Test
    @DisplayName("toClassName - empty string")
    void testToClassNameEmpty() throws Exception {
        assertEquals("", invoke("toClassName", ""));
    }

    // ==================== toCamelCase ====================

    @ParameterizedTest
    @CsvSource({
            "user_name, userName",
            "create_time, createTime",
            "order_item_detail, orderItemDetail",
            "name, name",
            "simple_column, simpleColumn",
    })
    @DisplayName("toCamelCase - snake_case to camelCase")
    void testToCamelCase(String columnName, String expected) throws Exception {
        assertEquals(expected, invoke("toCamelCase", columnName));
    }

    @Test
    @DisplayName("toCamelCase - leading underscore with capitals")
    void testToCamelCaseLeadingUnderscore() throws Exception {
        assertEquals("Test", invoke("toCamelCase", "_test"));
    }

    // ==================== toJavaType ====================

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "varchar(50)| String",
            "int| Integer",
            "bigint| Long",
            "decimal(10,2)| BigDecimal",
            "datetime| LocalDateTime",
            "date| LocalDate",
            "time| LocalTime",
            "tinyint(1)| Integer",
            "float| Float",
            "double| Double",
            "bit| Boolean",
            "text| String",
            "longtext| String",
    })
    @DisplayName("toJavaType - DB type to Java type")
    void testToJavaType(String dbType, String expected) throws Exception {
        assertEquals(expected, invoke("toJavaType", dbType));
    }

    @Test
    @DisplayName("toJavaType - null returns String")
    void testToJavaTypeNull() throws Exception {
        assertEquals("String", invoke("toJavaType", new Class[]{String.class}, new Object[]{null}));
    }

    @Test
    @DisplayName("toJavaType - unknown type returns String")
    void testToJavaTypeUnknown() throws Exception {
        assertEquals("String", invoke("toJavaType", "geometry"));
    }

    // ==================== inferQueryType ====================

    @ParameterizedTest
    @CsvSource({
            "datetime, BETWEEN",
            "timestamp, BETWEEN",
            "date, BETWEEN",
            "varchar(255), LIKE",
            "text, LIKE",
            "int, EQ",
            "bigint, EQ",
            "decimal, EQ",
    })
    @DisplayName("inferQueryType - infer search query type from DB type")
    void testInferQueryType(String dbType, String expected) throws Exception {
        assertEquals(expected, invoke("inferQueryType", dbType));
    }

    @Test
    @DisplayName("inferQueryType - null returns EQ")
    void testInferQueryTypeNull() throws Exception {
        assertEquals("EQ", invoke("inferQueryType", new Class[]{String.class}, new Object[]{null}));
    }

    // ==================== inferHtmlType ====================

    @ParameterizedTest
    @CsvSource({
            "text, textarea",
            "longtext, textarea",
            "datetime, datetime",
            "timestamp, datetime",
            "date, datetime",
            "time, datetime",
            "varchar(255), input",
            "int, input",
            "bigint, input",
    })
    @DisplayName("inferHtmlType - infer form control type from DB type")
    void testInferHtmlType(String dbType, String expected) throws Exception {
        assertEquals(expected, invoke("inferHtmlType", dbType));
    }

    @Test
    @DisplayName("inferHtmlType - null returns input")
    void testInferHtmlTypeNull() throws Exception {
        assertEquals("input", invoke("inferHtmlType", new Class[]{String.class}, new Object[]{null}));
    }

    // ==================== Reflection helpers ====================

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, String arg) throws Exception {
        Method m = TARGET.getDeclaredMethod(methodName, String.class);
        m.setAccessible(true);
        try {
            return (T) m.invoke(instance, arg);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, Class<?>[] paramTypes, Object[] args) throws Exception {
        Method m = TARGET.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return (T) m.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
