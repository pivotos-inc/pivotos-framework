package com.pivotos.system.service.impl;

import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.crypto.digest.BCrypt;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.auth.account.StpAppUtil;
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
        return doLogin(body, StpSysUtil.STP, StpSysUtil.TYPE);
    }

    @Override
    public LoginVO appLogin(LoginBody body) {
        return doLogin(body, StpAppUtil.STP, StpAppUtil.TYPE);
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

    @Override
    public void appLogout() {
        StpAppUtil.logout();
    }

    /**
     * 账密登录主流程：同一 sys_user 用户库，按账号体系发隔离 Token。
     * 用户不存在与密码错误统一报 2001，不泄露账号是否存在。
     */
    private LoginVO doLogin(LoginBody body, StpLogic stpLogic, String loginType) {
        SysUser user = userService.getByUsername(body.getUsername());
        if (user == null || !BCrypt.checkpw(body.getPassword(), user.getPassword())) {
            throw new ServiceException(SystemErrorCode.LOGIN_FAILED);
        }
        if (!Objects.equals(CommonStatusEnum.ENABLED.getValue(), user.getStatus())) {
            throw new ServiceException(SystemErrorCode.USER_DISABLED);
        }
        stpLogic.login(user.getId());
        AuthSessionHolder.saveLoginUser(stpLogic,
                new LoginUser(user.getId(), user.getUsername(), loginType, null));
        return new LoginVO(stpLogic.getTokenValue());
    }
}
