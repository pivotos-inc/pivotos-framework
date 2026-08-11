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
 * SubIntentValidator 确定性校验单测（S52 / 2.4-F5，不信 LLM）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubIntentValidator unit tests")
class SubIntentValidatorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SubIntentValidator validator;

    private SubIntentValidator validator() {
        if (validator == null) {
            validator = new SubIntentValidator(jdbcTemplate);
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

    private Map<String, Object> tableBlock(String tableName, String businessName, List<Map<String, Object>> cols) {
        Map<String, Object> b = new HashMap<>();
        b.put("tableName", tableName);
        b.put("businessName", businessName);
        b.put("tableComment", tableName);
        b.put("columns", cols);
        return b;
    }

    /** 合法主子意图：biz_order + biz_order_item(order_id) */
    private Map<String, Object> validIntent() {
        List<Map<String, Object>> mainCols = new ArrayList<>();
        mainCols.add(col("order_no", "orderNo"));
        List<Map<String, Object>> subCols = new ArrayList<>();
        Map<String, Object> fk = col("order_id", "orderId");
        fk.put("columnType", "bigint");
        fk.put("javaType", "Long");
        subCols.add(fk);
        subCols.add(col("product_name", "productName"));

        Map<String, Object> intent = new HashMap<>();
        intent.put("moduleName", "system");
        intent.put("functionName", "订单管理");
        intent.put("main", tableBlock("biz_order", "order", mainCols));
        intent.put("sub", tableBlock("biz_order_item", "orderItem", subCols));
        Map<String, Object> relation = new HashMap<>();
        relation.put("subFkName", "order_id");
        intent.put("relation", relation);
        return intent;
    }

    @Test
    @DisplayName("闭合主子意图校验通过")
    void testValidIntentPasses() {
        assertDoesNotThrow(() -> validator().validate(validIntent()));
    }

    @Test
    @DisplayName("关系不闭合（fk 列不在子表）抛 7011")
    void testRelationNotClosed() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        Map<String, Object> relation = (Map<String, Object>) intent.get("relation");
        relation.put("subFkName", "not_exist_fk");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator().validate(intent));
        assertEquals(CodingErrorCode.CODING_RELATION_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("主子同名抛 7011")
    void testSameTableName() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        Map<String, Object> sub = (Map<String, Object>) intent.get("sub");
        sub.put("tableName", "biz_order");
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("审计列混入抛 7011")
    void testAuditColumnRejected() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) intent.get("main");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) main.get("columns");
        cols.add(col("create_time", "createTime"));
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("非法表名（大写/注入字符）抛 7011")
    void testIllegalTableName() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) intent.get("main");
        main.put("tableName", "Biz_Order;DROP");
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("缺 main/sub/relation 结构抛 7011")
    void testMissingStructure() {
        Map<String, Object> intent = new HashMap<>();
        intent.put("moduleName", "system");
        intent.put("functionName", "订单管理");
        assertThrows(ServiceException.class, () -> validator().validate(intent));
    }

    @Test
    @DisplayName("fk 三键齐全且目标真实存在则保留")
    void testFkKeptWhenTargetExists() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) intent.get("main");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) main.get("columns");
        Map<String, Object> fkCol = col("dept_id", "deptId");
        fkCol.put("columnType", "bigint");
        fkCol.put("javaType", "Long");
        fkCol.put("fkTable", "sys_dept");
        fkCol.put("fkValueColumn", "id");
        fkCol.put("fkLabelColumn", "dept_name");
        cols.add(fkCol);
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Long.class), anyString(), anyString()))
                .thenReturn(1L);

        validator().validate(intent);
        assertEquals("sys_dept", fkCol.get("fkTable"));
        assertEquals("id", fkCol.get("fkValueColumn"));
    }

    @Test
    @DisplayName("fk 目标不存在则降级剥离三键")
    void testFkDegradedWhenTargetMissing() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) intent.get("main");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) main.get("columns");
        Map<String, Object> fkCol = col("dept_id", "deptId");
        fkCol.put("fkTable", "sys_ghost");
        fkCol.put("fkValueColumn", "id");
        fkCol.put("fkLabelColumn", "name");
        cols.add(fkCol);
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Long.class), anyString(), anyString()))
                .thenReturn(0L);

        validator().validate(intent);
        assertFalse(fkCol.containsKey("fkTable"));
        assertFalse(fkCol.containsKey("fkValueColumn"));
        assertFalse(fkCol.containsKey("fkLabelColumn"));
    }

    @Test
    @DisplayName("fk 配置不完整（缺显示列）直接剥离不探测")
    void testFkIncompleteStripped() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) intent.get("main");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) main.get("columns");
        Map<String, Object> fkCol = col("dept_id", "deptId");
        fkCol.put("fkTable", "sys_dept"); // 缺 value/label
        cols.add(fkCol);

        validator().validate(intent);
        assertFalse(fkCol.containsKey("fkTable"));
    }

    @Test
    @DisplayName("子表 fk 列上的 fk 下拉配置被剥离（语义冲突）")
    void testSubFkColumnFkConfigStripped() {
        Map<String, Object> intent = validIntent();
        @SuppressWarnings("unchecked")
        Map<String, Object> sub = (Map<String, Object>) intent.get("sub");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) sub.get("columns");
        Map<String, Object> fkCol = cols.get(0); // order_id
        fkCol.put("fkTable", "biz_order");
        fkCol.put("fkValueColumn", "id");
        fkCol.put("fkLabelColumn", "order_no");

        validator().validate(intent);
        assertFalse(fkCol.containsKey("fkTable"));
    }
}
