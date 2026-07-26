package com.pivotos.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.auth.support.AuthSessionHolder;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.constant.SystemConstants;
import com.pivotos.system.convert.UserConvert;
import com.pivotos.system.domain.dto.LoginBody;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.vo.LoginVO;
import com.pivotos.system.domain.vo.UserInfoVO;
import com.pivotos.system.service.MenuService;
import com.pivotos.system.service.RoleService;
import com.pivotos.system.service.SysLoginService;
import com.pivotos.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 登录认证编排实现 */
@Service
@RequiredArgsConstructor
public class SysLoginServiceImpl implements SysLoginService {

    private final UserService userService;
    private final RoleService roleService;
    private final MenuService menuService;
    private final UserConvert userConvert;

    @Override
    public LoginVO login(LoginBody body) {
        SysUser user = userService.getByUsername(body.getUsername());
        // 用户不存在与密码错误统一报 2001，不泄露账号是否存在
        if (user == null || !BCrypt.checkpw(body.getPassword(), user.getPassword())) {
            throw new ServiceException(SystemErrorCode.LOGIN_FAILED);
        }
        if (!Objects.equals(CommonStatusEnum.ENABLED.getValue(), user.getStatus())) {
            throw new ServiceException(SystemErrorCode.USER_DISABLED);
        }
        String token = StpSysUtil.login(user.getId());
        AuthSessionHolder.saveLoginUser(StpSysUtil.STP,
                new LoginUser(user.getId(), user.getUsername(), StpSysUtil.TYPE, null));
        return new LoginVO(token);
    }

    @Override
    public UserInfoVO getInfo() {
        Long userId = LoginContext.getUserId();
        SysUser user = userService.getById(userId);
        List<String> roleCodes = roleService.listRoleCodesByUserId(userId);
        boolean superAdmin = roleCodes.contains(SystemConstants.SUPER_ADMIN_ROLE);
        List<String> roles = superAdmin ? List.of(SystemConstants.SUPER_ADMIN_ROLE) : roleCodes;
        List<String> perms = superAdmin
                ? List.of(SystemConstants.SUPER_ADMIN_PERM)
                : menuService.listPermsByUserId(userId);
        return new UserInfoVO(userConvert.toVo(user), roles, perms);
    }

    @Override
    public void logout() {
        StpSysUtil.logout();
    }
}
