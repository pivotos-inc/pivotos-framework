package com.pivotos.starter.tenant.strategy;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.mybatis.config.properties.MybatisProperties;
import com.pivotos.starter.tenant.config.properties.TenantProperties;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;

/**
 * 字段隔离策略：共享库共享表，行级过滤由 MP TenantLineInnerInterceptor 完成，
 * apply/clear 为 no-op（拦截器在 SQL 解析期直接读 TenantContext）。
 * <p>本类同时是增强版 {@link TenantLineHandler}：替换 starter-mybatis 的默认实现
 * （@ConditionalOnMissingBean 守护，TenantAutoConfiguration 排序在 mybatis 之前），
 * 在默认"无上下文放行"语义上追加 ignore-tables 支持（内置 sys_* 平台共享表 + 用户追加）。
 */
public class ColumnTenantStrategy implements TenantStrategy, TenantLineHandler {

    private final TenantProperties tenantProperties;
    private final MybatisProperties mybatisProperties;

    public ColumnTenantStrategy(TenantProperties tenantProperties, MybatisProperties mybatisProperties) {
        this.tenantProperties = tenantProperties;
        this.mybatisProperties = mybatisProperties;
    }

    @Override
    public TenantMode mode() {
        return TenantMode.COLUMN;
    }

    @Override
    public void apply(Long tenantId) {
        // no-op：行级过滤由 MP 拦截器读 TenantContext 完成
    }

    @Override
    public void clear() {
        // no-op
    }

    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContext.get();
        return new LongValue(tenantId == null ? 0L : tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return mybatisProperties.getTenantColumn();
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (TenantContext.get() == null) {
            // 无租户上下文 → 全表放行（单租户行为兜底）
            return true;
        }
        Set<String> ignores = tenantProperties.effectiveIgnoreTables();
        return ignores.contains(tableName);
    }
}
