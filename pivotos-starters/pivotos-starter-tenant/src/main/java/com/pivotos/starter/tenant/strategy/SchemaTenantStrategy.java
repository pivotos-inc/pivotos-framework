package com.pivotos.starter.tenant.strategy;

import com.pivotos.starter.tenant.config.properties.TenantProperties;

import java.util.Map;

/**
 * Schema 隔离策略：同实例独立 schema，按 {@code pivotos.tenant.schema-map}
 * 路由到对应数据源（数据源 key 约定指向同实例不同库名的连接）。
 */
public class SchemaTenantStrategy extends AbstractRoutingTenantStrategy {

    private final TenantProperties properties;

    public SchemaTenantStrategy(TenantProperties properties) {
        this.properties = properties;
    }

    @Override
    public TenantMode mode() {
        return TenantMode.SCHEMA;
    }

    @Override
    protected Map<Long, String> tenantDsMap() {
        return properties.getSchemaMap();
    }

    /**
     * 启动实证日志（由自动配置注册后调用）
     */
    public void logEffective() {
        super.logEffective("schema");
    }
}
