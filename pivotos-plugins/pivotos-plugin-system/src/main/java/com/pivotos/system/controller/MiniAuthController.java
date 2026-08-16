package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpWxMiniUtil;
import com.pivotos.system.domain.dto.MiniBindBody;
import com.pivotos.system.domain.dto.MiniLoginBody;
import com.pivotos.system.domain.dto.MiniPhoneLoginBody;
import com.pivotos.system.domain.vo.MiniLoginVO;
import com.pivotos.system.domain.vo.UserInfoVO;
import com.pivotos.system.service.MiniAuthService;
import com.pivotos.system.service.SysLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信小程序认证接口（wx-mini-user 独立 Token 体系）。
 * 登录主链路：uni.login code → 已绑定直接进；未绑定 → 手机号授权 / 账密绑定。
 */
@Tag(name = "小程序认证", description = "微信小程序认证接口")
@RestController
@RequestMapping("/mini/auth")
@RequiredArgsConstructor
public class MiniAuthController {

    private final MiniAuthService miniAuthService;
    private final SysLoginService loginService;

    /** 小程序登录（code2session） */
    @Operation(summary = "小程序登录（code2session）")
    @PostMapping("/login")
    public R<MiniLoginVO> login(@Validated @RequestBody MiniLoginBody body) {
        return R.ok(miniAuthService.login(body.getCode()));
    }

    /** 手机号授权登录（未注册自动建档） */
    @Operation(summary = "手机号授权登录（未注册自动建档）")
    @PostMapping("/phone")
    public R<MiniLoginVO> phone(@Validated @RequestBody MiniPhoneLoginBody body) {
        return R.ok(miniAuthService.phoneLogin(body.getLoginCode(), body.getPhoneCode()));
    }

    /** 账密绑定登录 */
    @Operation(summary = "账密绑定登录")
    @PostMapping("/bind")
    public R<MiniLoginVO> bind(@Validated @RequestBody MiniBindBody body) {
        return R.ok(miniAuthService.bindAccount(body.getLoginCode(), body.getUsername(), body.getPassword()));
    }

    /** 退出登录 */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    @SaCheckLogin(type = StpWxMiniUtil.TYPE)
    public R<Void> logout() {
        StpWxMiniUtil.logout();
        return R.ok();
    }

    /** 当前登录用户信息 */
    @Operation(summary = "当前登录用户信息")
    @GetMapping("/getInfo")
    @SaCheckLogin(type = StpWxMiniUtil.TYPE)
    public R<UserInfoVO> getInfo() {
        return R.ok(loginService.getInfo());
    }
}
