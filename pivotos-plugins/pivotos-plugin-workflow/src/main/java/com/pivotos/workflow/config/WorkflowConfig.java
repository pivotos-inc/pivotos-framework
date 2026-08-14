package com.pivotos.workflow.config;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import org.dromara.warm.flow.core.handler.PermissionHandler;
import org.dromara.warm.flow.core.handler.TenantHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 工作流插件配置入口。
 * <p>
 * S55：WarmFlow starter 通过 AutoConfiguration 装配 DefService / InsService / TaskService 等。
 * S56：流程管理 Controller 由 @Component 扫描自动注册。
 * S57：注册 TenantHandler + PermissionHandler 桥接，将 PivotOS 登录上下文注入 WarmFlow。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowConfig {

    /**
     * 租户桥接：PivotOS LoginUser.tenantId（Long）→ WarmFlow tenant_id（VARCHAR）。
     * 单租户模式（tenantId 为 null）返回 "default" 占位，确保 WarmFlow 不会因 null 报错。
     */
    @Bean
    public TenantHandler pivotosTenantHandler() {
        return () -> {
            LoginUser user = LoginContext.get();
            if (user != null && user.getTenantId() != null) {
                return String.valueOf(user.getTenantId());
            }
            return "default";
        };
    }

    /**
     * 权限/处理人桥接：将当前登录用户 ID 作为 WarmFlow handler 标识。
     * <p>
     * getHandler() 返回当前用户 ID 字符串（用于 Task 的 handler 字段匹配）；
     * permissions() 返回当前用户 ID 作为权限标识（用于 Task.permissionList 匹配）。
     * WarmFlow 在查询待办时会用 permissions() 的返回值与 flow_task.permission_list 做交集过滤。
     */
    @Bean
    public PermissionHandler pivotosPermissionHandler() {
        return new PermissionHandler() {

            @Override
            public String getHandler() {
                Long userId = LoginContext.getUserId();
                return userId != null ? String.valueOf(userId) : "anonymous";
            }

            @Override
            public List<String> permissions() {
                Long userId = LoginContext.getUserId();
                if (userId == null) {
                    return List.of();
                }
                return List.of(String.valueOf(userId));
            }
        };
    }
}
