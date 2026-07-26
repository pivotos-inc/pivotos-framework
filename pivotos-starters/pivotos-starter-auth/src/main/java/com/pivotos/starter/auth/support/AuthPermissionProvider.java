package com.pivotos.starter.auth.support;

import java.util.List;

/**
 * 权限数据源提供者：框架只定义契约，
 * 由 system Plugin（S7）实现，从权限服务读取角色/权限串。
 */
public interface AuthPermissionProvider {

    /**
     * 权限串列表，如 ["system:user:list", "system:user:add"]
     */
    List<String> getPermissions(Object loginId, String loginType);

    /**
     * 角色标识列表，如 ["super_admin"]
     */
    List<String> getRoles(Object loginId, String loginType);
}
