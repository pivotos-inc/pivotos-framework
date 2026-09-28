package com.pivotos.system.service.impl;

import cn.dev33.satoken.session.SaSession;
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
import com.pivotos.system.service.LoginLogService;
import com.pivotos.system.service.MenuService;
import com.pivotos.system.service.RoleService;
import com.pivotos.system.service.SysLoginService;
import com.pivotos.system.service.TenantService;
import com.pivotos.system.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    private final LoginLogService loginLogService;
    private final TenantService tenantService;
    private final HttpServletRequest request;

    /** S106 增量①：多租户总开关（读 pivotos.tenant.enabled，与 starter-tenant 同键；false 时零行为差异） */
    @Value("${pivotos.tenant.enabled:false}")
    private boolean tenantEnabled;

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
            loginLogService.record(body.getUsername(), false, SystemErrorCode.LOGIN_FAILED.getMsg());
            throw new ServiceException(SystemErrorCode.LOGIN_FAILED);
        }
        if (!Objects.equals(CommonStatusEnum.ENABLED.getValue(), user.getStatus())) {
            loginLogService.record(body.getUsername(), false, SystemErrorCode.USER_DISABLED.getMsg());
            throw new ServiceException(SystemErrorCode.USER_DISABLED);
        }
        // S106 增量①：租户接线——启用多租户且用户有绑定时，校验租户可用性并填充 LoginUser.tenantId；
        // tenant.enabled=false 或用户无绑定（平台用户）时 tenantId 恒 null，与既有行为逐字节一致。
        Long tenantId = null;
        if (tenantEnabled && user.getTenantId() != null) {
            try {
                tenantId = tenantService.requireActiveTenant(user.getTenantId()).getId();
            } catch (ServiceException e) {
                loginLogService.record(body.getUsername(), false, e.getMessage());
                throw e;
            }
        }
        stpLogic.login(user.getId());
        loginLogService.record(user.getUsername(), true, null);
        String tokenValue = stpLogic.getTokenValue();
        SaSession tokenSession = stpLogic.getTokenSessionByToken(tokenValue);
        AuthSessionHolder.saveLoginUser(tokenSession,
                new LoginUser(user.getId(), user.getUsername(), loginType, tenantId));
        // S29：写入登录元信息（IP + 时间）到 Token Session，供在线用户列表使用
        tokenSession.set("LOGIN_IP", getClientIP(request));
        long now = System.currentTimeMillis();
        tokenSession.set("LOGIN_TIME", now);
        tokenSession.set("LAST_ACTIVE_TIME", now);
        return new LoginVO(tokenValue);
    }

    /** 从 HttpServletRequest 提取客户端真实 IP */
    private static String getClientIP(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP",
                "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR"};
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
