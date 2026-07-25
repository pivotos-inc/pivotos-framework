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
}
