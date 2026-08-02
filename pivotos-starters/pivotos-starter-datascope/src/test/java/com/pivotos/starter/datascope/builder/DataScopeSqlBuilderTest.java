package com.pivotos.starter.datascope.builder;

import com.pivotos.starter.datascope.context.DataScopeContext.DataScopeInfo;
import com.pivotos.starter.datascope.enums.DataScopeEnum;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataScopeSqlBuilder 单元测试 —— 覆盖 5 种数据范围模式 + 边界条件。
 */
@DisplayName("DataScopeSqlBuilder 数据权限 SQL 表达式构建")
class DataScopeSqlBuilderTest {

    private static final String DEPT_COL = "dept_id";
    private static final String USER_COL = "id";

    // ==================== null / skip 场景 ====================

    @Nested
    @DisplayName("null/skip 场景 → 返回 null（不过滤）")
    class NullOrSkip {

        @Test
        @DisplayName("scope == null → null")
        void nullScope() {
            assertNull(DataScopeSqlBuilder.buildWhereExpression(null, DEPT_COL, USER_COL));
        }

        @Test
        @DisplayName("scope.isSkip() → null")
        void skipScope() {
            DataScopeInfo scope = DataScopeInfo.skip();
            assertNull(DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL));
        }

        @Test
        @DisplayName("skip with userId → null")
        void skipWithUserId() {
            DataScopeInfo scope = DataScopeInfo.skip(1L);
            assertNull(DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL));
        }
    }

    // ==================== ALL 模式 ====================

    @Nested
    @DisplayName("ALL 模式（dataScope=1）→ null")
    class All {

        @Test
        @DisplayName("ALL 返回 null（全量数据，不过滤）")
        void allReturnsNull() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.ALL, 1L, 10L, null);
            assertNull(DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL));
        }
    }

    // ==================== DEPT 模式 ====================

    @Nested
    @DisplayName("DEPT 模式（dataScope=2）→ dept_id = xxx")
    class Dept {

        @Test
        @DisplayName("DEPT 生成 dept_id = deptId")
        void deptEqualsTo() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.DEPT, 1L, 100L, null);
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL);

            assertNotNull(expr);
            assertTrue(expr instanceof EqualsTo);
            assertEquals("dept_id = 100", expr.toString());
        }
    }

    // ==================== DEPT_AND_BELOW 模式 ====================

    @Nested
    @DisplayName("DEPT_AND_BELOW 模式（dataScope=3）→ dept_id IN (...)")
    class DeptAndBelow {

        @Test
        @DisplayName("DEPT_AND_BELOW 生成 IN 表达式")
        void deptAndBelowIn() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.DEPT_AND_BELOW, 1L, 100L,
                    Set.of(100L, 101L, 102L));
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL);

            assertNotNull(expr);
            assertTrue(expr instanceof InExpression);
            assertTrue(expr.toString().startsWith("dept_id IN ("));
            assertTrue(expr.toString().contains("100"));
            assertTrue(expr.toString().contains("101"));
            assertTrue(expr.toString().contains("102"));
        }

        @Test
        @DisplayName("DEPT_AND_BELOW 空部门集合 → 1=0")
        void deptAndBelowEmptySet() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.DEPT_AND_BELOW, 1L, 100L,
                    Set.of());
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL);

            assertNotNull(expr);
            assertEquals("1 = 0", expr.toString());
        }

        @Test
        @DisplayName("DEPT_AND_BELOW null 部门集合 → 1=0")
        void deptAndBelowNullSet() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.DEPT_AND_BELOW, 1L, 100L, null);
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL);

            assertNotNull(expr);
            assertEquals("1 = 0", expr.toString());
        }
    }

    // ==================== SELF 模式 ====================

    @Nested
    @DisplayName("SELF 模式（dataScope=4）→ userColumn = userId")
    class Self {

        @Test
        @DisplayName("SELF 生成 id = userId")
        void selfEqualsTo() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.SELF, 42L, 100L, null);
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL);

            assertNotNull(expr);
            assertTrue(expr instanceof EqualsTo);
            assertEquals("id = 42", expr.toString());
        }

        @Test
        @DisplayName("SELF 支持自定义 userColumn")
        void selfWithCustomColumn() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.SELF, 42L, 100L, null);
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, "create_by");

            assertNotNull(expr);
            assertEquals("create_by = 42", expr.toString());
        }
    }

    // ==================== CUSTOM 模式 ====================

    @Nested
    @DisplayName("CUSTOM 模式（dataScope=5）→ dept_id IN (自定义)")
    class Custom {

        @Test
        @DisplayName("CUSTOM 生成 IN 表达式")
        void customIn() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.CUSTOM, 1L, 100L,
                    Set.of(200L, 300L));
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL);

            assertNotNull(expr);
            assertTrue(expr instanceof InExpression);
            assertEquals("dept_id IN (200, 300)", expr.toString());
        }

        @Test
        @DisplayName("CUSTOM 空自定义集合 → 1=0")
        void customEmptySet() {
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.CUSTOM, 1L, 100L, Set.of());
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, DEPT_COL, USER_COL);

            assertNotNull(expr);
            assertEquals("1 = 0", expr.toString());
        }
    }

    // ==================== 综合场景 ====================

    @Nested
    @DisplayName("自定义列名场景")
    class CustomColumns {

        @Test
        @DisplayName("支持自定义 deptColumn / userColumn")
        void customColumns() {
            // DEPT 模式使用自定义列名
            DataScopeInfo scope = DataScopeInfo.of(DataScopeEnum.DEPT, 1L, 500L, null);
            Expression expr = DataScopeSqlBuilder.buildWhereExpression(scope, "org_id", "creator_id");

            assertNotNull(expr);
            assertEquals("org_id = 500", expr.toString());
        }
    }
}
