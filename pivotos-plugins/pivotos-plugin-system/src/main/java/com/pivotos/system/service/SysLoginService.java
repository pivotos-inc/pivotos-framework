package com.pivotos.system.service;

import com.pivotos.system.domain.dto.LoginBody;
import com.pivotos.system.domain.vo.LoginVO;
import com.pivotos.system.domain.vo.UserInfoVO;

/** 登录认证编排（账密校验 → Sa-Token 登录 → 会话写入） */
public interface SysLoginService {

    /** 账号密码登录（管理端 sys-user 体系），成功返回 Token */
    LoginVO login(LoginBody body);

    /** 账号密码登录（移动端 App 体系，同一 sys_user 用户库，app-user Token 体系） */
    LoginVO appLogin(LoginBody body);

    /** 当前登录用户信息（用户 + 角色 + 权限串），LoginContext 驱动，各体系通用 */
    UserInfoVO getInfo();

    /** 退出登录（管理端体系） */
    void logout();

    /** 退出登录（App 体系） */
    void appLogout();
}
