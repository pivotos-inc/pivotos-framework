package com.pivotos.workflow.config;

import org.springframework.context.annotation.Configuration;

/**
 * 工作流插件配置入口。
 * <p>
 * S55 引擎底座 Sprint：WarmFlow starter 通过自身 AutoConfiguration 自动装配引擎 Bean
 * （DefService / InsService / TaskService 等），本类预留 PivotOS 特有的桥接配置
 * （如 TenantHandler），S56/S57 逐步补充。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowConfig {

    // ---- S55 M4：TenantHandler 桥接（PivotOS BIGINT tenant → WarmFlow VARCHAR tenant_id） ----

    // ---- S56 补充：流程管理 Controller 相关配置 ----

    // ---- S57 补充：审批流转 / 消息通知集成配置 ----
}
