package com.pivotos.starter.auth.support;

import cn.dev33.satoken.stp.StpInterface;

import java.util.List;

/**
 * Sa-Token 权限数据桥：@SaCheckPermission / @SaCheckRole 的数据来源，
 * 委托给 AuthPermissionProvider（system Plugin 提供真实实现）。
 */
public class StpInterfaceImpl implements StpInterface {

    private final AuthPermissionProvider provider;

    public StpInterfaceImpl(AuthPermissionProvider provider) {
        this.provider = provider;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return provider.getPermissions(loginId, loginType);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return provider.getRoles(loginId, loginType);
    }
}
