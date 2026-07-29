package com.pivotos.starter.tenant.strategy;

import com.pivotos.starter.tenant.config.properties.TenantProperties;

import java.util.Map;

/**
 * 数据源隔离策略：按 {@code pivotos.tenant.datasource-map} 路由到独立数据源
 * （可指向完全不同实例，适用于大租户独立部署）。
 */
public class DatasourceTenantStrategy extends AbstractRoutingTenantStrategy {

    private final TenantProperties properties;

    public DatasourceTenantStrategy(TenantProperties properties) {
        this.properties = properties;
    }

    @Override
    public TenantMode mode() {
        return TenantMode.DATASOURCE;
    }

    @Override
    protected Map<Long, String> tenantDsMap() {
        return properties.getDatasourceMap();
    }

    /**
     * 启动实证日志（由自动配置注册后调用）
     */
    public void logEffective() {
        super.logEffective("datasource");
    }
}
