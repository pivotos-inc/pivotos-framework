package com.pivotos.starter.datascope.interceptor;

import com.pivotos.common.core.annotation.DataScope;
import com.pivotos.starter.datascope.builder.DataScopeSqlBuilder;
import com.pivotos.starter.datascope.context.DataScopeContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据权限 SQL 注入 MyBatis 插件。
 * <p>
 * 拦截 StatementHandler.prepare，在 SQL 发送到数据库前注入数据权限 WHERE 条件。
 * 使用 JSqlParser 解析 SQL 语义树，安全注入条件表达式。
 * <p>
 * 与 TenantLineInnerInterceptor 共存：
 * tenant 拦截器在 executor.query 阶段注入 tenant_id 过滤（列级），
 * 本插件在 statementHandler.prepare 阶段注入数据权限条件（行级），
 * 两者作用于同一 SQL 的不同部分，互不冲突。
 *
 * @author PivotOS Team
 */
@Intercepts({
    @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class DataPermissionInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(DataPermissionInterceptor.class);

    /** 缓存：MappedStatement id → @DataScope 注解（HashMap 支持 null 值） */
    private final Map<String, DataScope> annotationCache = new HashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(handler);

        // 跳过非 RoutingStatementHandler 场景
        MappedStatement ms;
        try {
            ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        } catch (Exception e) {
            return invocation.proceed();
        }
        if (ms == null) {
            return invocation.proceed();
        }

        DataScopeContext.DataScopeInfo scope = DataScopeContext.get();
        if (scope == null || scope.isSkip()) {
            return invocation.proceed();
        }

        DataScope annotation = getAnnotation(ms);
        if (annotation == null) {
            return invocation.proceed();
        }

        BoundSql boundSql = (BoundSql) metaObject.getValue("delegate.boundSql");
        String originalSql = boundSql.getSql();

        String modifiedSql = injectDataScopeCondition(originalSql, scope, annotation);
        metaObject.setValue("delegate.boundSql.sql", modifiedSql);

        if (log.isDebugEnabled()) {
            log.debug("[PivotOS] 数据权限 SQL 增强：{} → {}", ms.getId(), modifiedSql);
        }

        return invocation.proceed();
    }

    /**
     * 使用 JSqlParser 在 SQL WHERE 子句中注入数据权限条件
     */
    private String injectDataScopeCondition(String originalSql, DataScopeContext.DataScopeInfo scope,
                                             DataScope annotation) {
        try {
            var statement = CCJSqlParserUtil.parse(originalSql);
            if (!(statement instanceof Select select)) {
                return originalSql;
            }
            if (!(select.getSelectBody() instanceof PlainSelect plainSelect)) {
                return originalSql;
            }

            Expression scopeExpr = DataScopeSqlBuilder.buildWhereExpression(
                    scope, annotation.deptColumn(), annotation.userColumn());
            if (scopeExpr == null) {
                return originalSql;
            }

            Expression originalWhere = plainSelect.getWhere();
            if (originalWhere != null) {
                plainSelect.setWhere(new AndExpression(originalWhere, scopeExpr));
            } else {
                plainSelect.setWhere(scopeExpr);
            }

            return select.toString();
        } catch (Exception e) {
            log.warn("[PivotOS] 数据权限 SQL 注入失败，回退到原始 SQL：{}", e.getMessage());
            return originalSql;
        }
    }

    /**
     * 获取 @DataScope 注解（带缓存）
     */
    private DataScope getAnnotation(MappedStatement ms) {
        String msId = ms.getId();
        if (annotationCache.containsKey(msId)) {
            return annotationCache.get(msId);
        }

        String className = msId.substring(0, msId.lastIndexOf('.'));
        try {
            Class<?> mapperClass = Class.forName(className);
            DataScope classAnnotation = mapperClass.getAnnotation(DataScope.class);
            annotationCache.put(msId, classAnnotation);
            return classAnnotation;
        } catch (ClassNotFoundException e) {
            annotationCache.put(msId, null);
            return null;
        }
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof StatementHandler) {
            return Plugin.wrap(target, this);
        }
        return target;
    }
}
