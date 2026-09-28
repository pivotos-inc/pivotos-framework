package com.pivotos.ai.coding.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 修改型能力配置装配。
 *
 * <p>独立配置类而非并入 LocateConfig：定位可以只开定位（只读），修改型涉及写工程文件，
 * 两个开关必须能独立关闭。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@Configuration
@EnableConfigurationProperties(ModifyProperties.class)
public class ModifyConfig {
}
