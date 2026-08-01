package com.pivotos.starter.datascope.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据权限配置属性
 * <p>
 * 命名空间：pivotos.datascope
 *
 * @author PivotOS Team
 */
@ConfigurationProperties(prefix = "pivotos.datascope")
public class DataScopeProperties {

    /** 是否启用数据权限功能（默认 true） */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
