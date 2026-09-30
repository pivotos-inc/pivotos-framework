package com.pivotos.system.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** system 插件配置属性登记 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WechatMiniProperties.class, OperLogSearchProperties.class})
public class SystemPropertiesConfig {
}
