package com.pivotos.system.config;

import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.auth.support.AuthPermissionProvider;
import com.pivotos.system.constant.SystemConstants;
import com.pivotos.system.service.MenuService;
import com.pivotos.system.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * system 插件权限数据源
 *
 * <p>注册后替代 starter-auth 的空实现，{@code @SaCheckPermission} /
 * {@code @SaCheckRole} 的判定数据全部来自 sys_* 表。
 */
@Component
@RequiredArgsConstructor
public class SystemPermissionProvider implements AuthPermissionProvider {

    private final RoleService roleService;
    private final MenuService menuService;

    @Override
    public List<String> getPermissions(Object loginId, String loginType) {
        // 非管理端账号（app-user / wx-mini-user）暂不发放 system 域权限
        if (!StpSysUtil.TYPE.equals(loginType)) {
            return List.of();
        }
        return menuService.listPermsByUserId(Long.valueOf(loginId.toString()));
    }

    @Override
    public List<String> getRoles(Object loginId, String loginType) {
        if (!StpSysUtil.TYPE.equals(loginType)) {
            return List.of();
        }
        List<String> roleCodes = roleService.listRoleCodesByUserId(Long.valueOf(loginId.toString()));
        return roleCodes.contains(SystemConstants.SUPER_ADMIN_ROLE)
                ? List.of(SystemConstants.SUPER_ADMIN_ROLE)
                : roleCodes;
    }
}
