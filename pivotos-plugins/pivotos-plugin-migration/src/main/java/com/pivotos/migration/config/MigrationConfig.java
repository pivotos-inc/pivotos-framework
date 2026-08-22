package com.pivotos.migration.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 迁移插件配置类。
 */
@Configuration
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationConfig {
}
