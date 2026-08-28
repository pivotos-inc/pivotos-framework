package com.pivotos.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PivotOS 管理端单体启动器
 *
 * 扫描范围约定（S8 决议）：
 * - Starter（com.pivotos.starter.*）只走 AutoConfiguration.imports 自动装配，严禁组件扫描
 * - 每个 Plugin 的根包在两处显式登记；新增 Plugin 时各加一行（S13 范式将固化此约定）：
 *   ① scanBasePackages：组件扫描（Controller/Service/Config）
 *   ② @AutoConfigurationPackage.basePackages：MyBatis-Plus @Mapper 接口自动扫描的包来源
 *   注意：两者相互独立，scanBasePackages 不会自动带进 AutoConfigurationPackages（S8 踩坑 2）
 */
@SpringBootApplication(scanBasePackages = {"com.pivotos.server", "com.pivotos.system", "com.pivotos.message", "com.pivotos.file", "com.pivotos.ai", "com.pivotos.generator", "com.pivotos.monitor", "com.pivotos.workflow", "com.pivotos.ai.kb", "com.pivotos.docsync", "com.pivotos.migration", "com.pivotos.mind"})
@AutoConfigurationPackage(basePackages = {"com.pivotos.server", "com.pivotos.system", "com.pivotos.message", "com.pivotos.file", "com.pivotos.ai", "com.pivotos.generator", "com.pivotos.monitor", "com.pivotos.workflow", "com.pivotos.ai.kb", "com.pivotos.docsync", "com.pivotos.migration", "com.pivotos.mind"})
public class PivotOsAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(PivotOsAdminApplication.class, args);
    }
}
