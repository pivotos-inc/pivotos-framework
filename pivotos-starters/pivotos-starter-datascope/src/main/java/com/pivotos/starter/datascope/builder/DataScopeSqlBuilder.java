package com.pivotos.starter.datascope.builder;

import com.pivotos.starter.datascope.context.DataScopeContext;
import com.pivotos.starter.datascope.enums.DataScopeEnum;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;

import java.util.Set;

/**
 * 数据权限 SQL WHERE 表达式构建器。
 * <p>
 * 根据 {@link DataScopeContext.DataScopeInfo} 中的 dataScope 和部门/用户信息，
 * 生成对应的 JSqlParser WHERE 表达式，供拦截器注入 SQL。
 *
 * @author PivotOS Team
 */
public final class DataScopeSqlBuilder {

    private DataScopeSqlBuilder() {
    }

    /**
     * 构建数据权限 WHERE 表达式。
     *
     * @param scope      数据权限上下文
     * @param deptColumn 部门字段名（如 dept_id）
     * @param userColumn 用户字段名（如 create_by）
     * @return SQL WHERE 表达式，若无需过滤则返回 null
     */
    public static Expression buildWhereExpression(DataScopeContext.DataScopeInfo scope,
                                                   String deptColumn, String userColumn) {
        if (scope == null || scope.isSkip()) {
            return null;
        }

        DataScopeEnum ds = scope.getDataScope();
        if (ds == null || ds == DataScopeEnum.ALL) {
            return null;
        }

        return switch (ds) {
            case DEPT -> new EqualsTo(new Column(deptColumn), new LongValue(scope.getDeptId()));
            case DEPT_AND_BELOW -> buildInExpression(deptColumn, scope.getVisibleDeptIds());
            case SELF -> new EqualsTo(new Column(userColumn), new LongValue(scope.getUserId()));
            case CUSTOM -> buildInExpression(deptColumn, scope.getVisibleDeptIds());
            default -> null;
        };
    }

    /**
     * 构建 IN (id1, id2, ...) 表达式。
     * 空集合返回 1=0（不匹配任何数据）。
     */
    private static Expression buildInExpression(String column, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new EqualsTo(new LongValue(1), new LongValue(0));
        }
        ExpressionList<LongValue> list = new ExpressionList<>();
        for (Long id : ids) {
            list.add(new LongValue(id));
        }
        return new InExpression(new Column(column), new ParenthesedExpressionList<>(list));
    }
}
