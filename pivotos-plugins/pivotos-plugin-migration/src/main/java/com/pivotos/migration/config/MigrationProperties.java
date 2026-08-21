package com.pivotos.migration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 迁移插件配置属性（{@code pivotos.migration.*}）。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.migration")
public class MigrationProperties {

    /** 本地工作目录：上传源码、解析产物、生成代码均存放于此 */
    private String workspace = "./data/migration";
}
