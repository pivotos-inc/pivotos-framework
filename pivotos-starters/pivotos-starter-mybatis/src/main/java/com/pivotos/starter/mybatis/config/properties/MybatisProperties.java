package com.pivotos.starter.mybatis.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ORM 底座配置
 */
@Data
@ConfigurationProperties(prefix = "pivotos.mybatis")
public class MybatisProperties {

    /** 字段加密密钥（16/24/32 位），不配置则字段加密不生效 */
    private String fieldEncryptKey;

    /** 租户字段名 */
    private String tenantColumn = "tenant_id";

    /**
     * 期望库名（L9）：配置后启动期会与「生效数据源 URL」里的库名比对，不一致直接启动失败。
     * 留空 = 只打印溯源日志不阻断（默认，避免误伤既有环境）。
     */
    private String expectedDatabase;
}
