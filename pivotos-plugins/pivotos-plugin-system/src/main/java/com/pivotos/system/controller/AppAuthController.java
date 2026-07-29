package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpAppUtil;
import com.pivotos.system.domain.dto.LoginBody;
import com.pivotos.system.domain.vo.LoginVO;
import com.pivotos.system.domain.vo.UserInfoVO;
import com.pivotos.system.service.SysLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 移动端 App 认证接口：与 PC 端同一 sys_user 用户库，
 * 但走 app-user 独立 Token 体系（Token 与 PC 互不通认，踢登互不影响）。
 */
@RestController
@RequestMapping("/app/auth")
@RequiredArgsConstructor
public class AppAuthController {

    private final SysLoginService loginService;

    /** 账号密码登录 */
    @PostMapping("/login")
    public R<LoginVO> login(@Validated @RequestBody LoginBody body) {
        return R.ok(loginService.appLogin(body));
    }

    /** 退出登录 */
    @PostMapping("/logout")
    @SaCheckLogin(type = StpAppUtil.TYPE)
    public R<Void> logout() {
        loginService.appLogout();
        return R.ok();
    }

    /** 当前登录用户信息 */
    @GetMapping("/getInfo")
    @SaCheckLogin(type = StpAppUtil.TYPE)
    public R<UserInfoVO> getInfo() {
        return R.ok(loginService.getInfo());
    }
}
