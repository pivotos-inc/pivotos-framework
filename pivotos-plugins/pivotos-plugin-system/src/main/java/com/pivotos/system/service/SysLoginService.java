package com.pivotos.system.service;

import com.pivotos.system.domain.dto.LoginBody;
import com.pivotos.system.domain.vo.LoginVO;
import com.pivotos.system.domain.vo.UserInfoVO;

/** 登录认证编排（账密校验 → Sa-Token 登录 → 会话写入） */
public interface SysLoginService {

    /** 账号密码登录，成功返回 Token */
    LoginVO login(LoginBody body);

    /** 当前登录用户信息（用户 + 角色 + 权限串） */
    UserInfoVO getInfo();

    /** 退出登录 */
    void logout();
}
