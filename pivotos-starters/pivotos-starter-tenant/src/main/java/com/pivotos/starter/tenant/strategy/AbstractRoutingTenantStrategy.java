package com.pivotos.starter.tenant.strategy;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.pivotos.starter.core.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 路由型租户策略基类（SCHEMA / DATASOURCE 共用）：
 * apply 将租户映射的数据源 key 压入 dynamic-datasource 路由上下文，
 * clear 在 TenantContext scope 内按同一映射判定后出栈——无需 ThreadLocal 记录，
 * 与 dynamic-datasource 的栈式嵌套语义兼容（@DS 内层切换不受影响）。
 * <p>未配置映射的租户留在 primary 数据源（平台库语义）。
 * <p>本类同时是放行版 {@link TenantLineHandler}：schema/datasource 模式下隔离已由
 * 数据源路由完成，MP 行级过滤必须整体放行（否则会给无 tenant_id 列的表追加非法条件）；
 * 作为 TenantLineHandler Bean 注册后，starter-mybatis 默认实现按 @ConditionalOnMissingBean 让位。
 * <p>注意：路由上下文是 dynamic-datasource 的 ThreadLocal 实现，
 * ContextExecutor 异步任务不会自动带出租源切换，异步跨租户查询需显式 @DS 或 push/poll（README 已说明）。
 */
public abstract class AbstractRoutingTenantStrategy implements TenantStrategy, TenantLineHandler {

    private static final Logger log = LoggerFactory.getLogger(AbstractRoutingTenantStrategy.class);

    /**
     * 租户 → 数据源 key 映射表
     */
    protected abstract Map<Long, String> tenantDsMap();

    @Override
    public void apply(Long tenantId) {
        String dsKey = tenantDsMap().get(tenantId);
        if (dsKey != null) {
            DynamicDataSourceContextHolder.push(dsKey);
        }
    }

    @Override
    public void clear() {
        Long tenantId = TenantContext.get();
        if (tenantId != null && tenantDsMap().containsKey(tenantId)) {
            DynamicDataSourceContextHolder.poll();
        }
    }

    // ===== TenantLineHandler：路由模式下行级过滤整体放行 =====

    @Override
    public Expression getTenantId() {
        // 永远不会被使用（ignoreTable 恒 true），防御性返回 0
        return new LongValue(0L);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return true;
    }

    /**
     * 启动日志实证（历史经验：第三方能力必须日志 + 实证确认生效）
     */
    protected void logEffective(String modeName) {
        Map<Long, String> map = tenantDsMap();
        log.info("[PivotOS] 多租户 {} 模式路由表生效：{} 条映射 {}", modeName, map.size(), map);
        if (map.isEmpty()) {
            log.warn("[PivotOS] 多租户 {} 模式路由表为空，所有租户将落在 primary 数据源", modeName);
        }
    }
}
