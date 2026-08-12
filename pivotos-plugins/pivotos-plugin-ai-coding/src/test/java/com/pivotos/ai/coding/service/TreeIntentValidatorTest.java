package com.pivotos.ai.coding.service;

import com.pivotos.ai.coding.api.constant.CodingErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * TreeIntentValidator 确定性校验单测（S54 / tree intent，不信 LLM）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TreeIntentValidator unit tests")
class TreeIntentValidatorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TreeIntentValidator validator;

    private TreeIntentValidator validator() {
        if (validator == null) {
            validator = new TreeIntentValidator(jdbcTemplate);
        }
        return validator;
    }

    private Map<String, Object> col(String columnName, String javaField) {
        Map<String, Object> c = new HashMap<>();
        c.put("columnName", columnName);
        c.put("columnType", "varchar(64)");
        c.put("columnComment", columnName);
        c.put("javaType", "String");
        c.put("javaField", javaField);
        return c;
    }

    private Map<String, Object> colTyped(String columnName, String javaField, String columnType, String javaType) {
        Map<String, Object> c = new HashMap<>();
        c.put("columnName", columnName);
        c.put("columnType", columnType);
        c.put("columnComment", columnName);
        c.put("javaType", javaType);
        c.put("javaField", javaField);
        return c;
    }

    /**
     * 合法树表意图：biz_category（category_code / parent_id / category_name）
     */
    private Map<String, Object> validIntent() {
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(col("category_code", "categoryCode"));
        columns.add(colTyped("parent_id", "parentId", "bigint", "Long"));
        columns.add(col("category_name", "categoryName"));
        columns.add(col("sort", "sort"));

        Map<String, Object> intent = new HashMap<>();
        intent.put("moduleName", "system");
        intent.put("functionName", "分类管理");
        intent.put("tableName", "biz_category");
        intent.put("businessName", "category");
        intent.put("tableComment", "商品分类");
        intent.put("treeCode", "category_code");
        intent.put("treeParentCode", "parent_id");
        intent.put("treeName", "category_name");
        intent.put("columns", columns);
        return intent;
    }

    @Test
    @DisplayName("合法树表意图校验通过")
    void testValidIntentPasses() {
        assertDoesNotThrow(() -> validator().validate(validIntent()));
    }

    @Test
    @DisplayName("treeCode 不在 columns 中抛 7011")
    void testTreeCodeNotInColumns() {
        Map<String, Object> intent = validIntent();
        intent.put("treeCode", "not_exist_code");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator().validate(intent));
        assertEquals(CodingErrorCode.CODING_RELATION_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("treeParentCode 不在 columns 中抛 7011")
    void testTreeParentCodeNotInColumns() {
        Map<String, Object> intent = validIntent();
        intent.put("treeParentCode", "ghost_parent");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator().validate(intent));
        assertEquals(CodingErrorCode.CODING_RELATION_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("treeName 不在 columns 中抛 7011")
    void testTreeNameNotInColumns() {
        Map<String, Object> intent = validIntent();
        intent.put("treeName", "ghost_name");
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("树三字段互异校验：treeCode == treeParentCode 抛 7011")
    void testTreeFieldsNotDistinct() {
        Map<String, Object> intent = validIntent();
        intent.put("treeParentCode", "category_code"); // 与 treeCode 重复
        ServiceException ex = assertThrows(ServiceException.class, () -> validator().validate(intent));
        assertEquals(CodingErrorCode.CODING_RELATION_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("审计列混入抛 7011")
    void testAuditColumnRejected() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) intent.get("columns");
        columns.add(col("create_time", "createTime"));
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("非法标识符（大写/特殊字符）抛 7011")
    void testIllegalIdentifier() {
        Map<String, Object> intent = validIntent();
        intent.put("tableName", "Biz_Category;DROP");
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("moduleName 非法抛 7011")
    void testIllegalModuleName() {
        Map<String, Object> intent = validIntent();
        intent.put("moduleName", "INVALID_MODULE");
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("columns 为空抛 7011")
    void testEmptyColumns() {
        Map<String, Object> intent = validIntent();
        intent.put("columns", List.of());
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("fk 配置不完整（缺显示列）直接剥离不抛异常")
    void testFkIncompleteStripped() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) intent.get("columns");
        Map<String, Object> fkCol = col("dept_id", "deptId");
        fkCol.put("fkTable", "sys_dept"); // 缺 value/label
        columns.add(fkCol);

        assertDoesNotThrow(() -> validator().validate(intent));
        assertFalse(fkCol.containsKey("fkTable"));
    }

    @Test
    @DisplayName("fk 三键齐全且目标真实存在则保留")
    void testFkKeptWhenTargetExists() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) intent.get("columns");
        Map<String, Object> fkCol = col("dept_id", "deptId");
        fkCol.put("fkTable", "sys_dept");
        fkCol.put("fkValueColumn", "id");
        fkCol.put("fkLabelColumn", "dept_name");
        columns.add(fkCol);
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Long.class), anyString(), anyString()))
                .thenReturn(1L);

        validator().validate(intent);
        assertEquals("sys_dept", fkCol.get("fkTable"));
    }

    @Test
    @DisplayName("fk 目标不存在则降级剥离三键")
    void testFkDegradedWhenTargetMissing() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) intent.get("columns");
        Map<String, Object> fkCol = col("dept_id", "deptId");
        fkCol.put("fkTable", "sys_ghost");
        fkCol.put("fkValueColumn", "id");
        fkCol.put("fkLabelColumn", "name");
        columns.add(fkCol);
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Long.class), anyString(), anyString()))
                .thenReturn(0L);

        validator().validate(intent);
        assertFalse(fkCol.containsKey("fkTable"));
        assertFalse(fkCol.containsKey("fkValueColumn"));
        assertFalse(fkCol.containsKey("fkLabelColumn"));
    }
}
