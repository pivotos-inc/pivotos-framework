package com.pivotos.ai.coding.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 定位能力配置装配。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Configuration
@EnableConfigurationProperties(LocateProperties.class)
public class LocateConfig {
}
